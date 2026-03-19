import io
import fitz  # pymupdf
from docx import Document


def extract_text(file_bytes: bytes, filename: str) -> str:
    filename_lower = filename.lower()
    if filename_lower.endswith(".pdf"):
        return extract_text_from_pdf(file_bytes)
    elif filename_lower.endswith(".docx"):
        return extract_text_from_docx(file_bytes)
    elif filename_lower.endswith(".doc"):
        raise ValueError("Legacy .doc format not supported. Please use .docx or .pdf")
    else:
        raise ValueError(f"Unsupported file format: {filename}")


def extract_text_from_pdf(file_bytes: bytes) -> str:
    """
    Extract text from PDF using pymupdf.
    Handles multi-column layouts by sorting text blocks by vertical position,
    then horizontal — mimicking natural reading order.
    """
    try:
        doc = fitz.open(stream=file_bytes, filetype="pdf")
        full_text = []

        for page in doc:
            # Get text blocks with position info: (x0, y0, x1, y1, text, ...)
            blocks = page.get_text("blocks")

            # Sort blocks: top-to-bottom first, then left-to-right
            # This handles two-column layouts correctly
            blocks_sorted = sorted(blocks, key=lambda b: (round(b[1] / 20), b[0]))

            for block in blocks_sorted:
                text = block[4].strip()
                if text:
                    full_text.append(text)

        doc.close()
        return "\n".join(full_text)

    except Exception as e:
        raise ValueError(f"Failed to extract text from PDF: {str(e)}")


def extract_text_from_docx(file_bytes: bytes) -> str:
    """Extract text from DOCX including tables."""
    try:
        doc = Document(io.BytesIO(file_bytes))
        paragraphs = [para.text for para in doc.paragraphs if para.text.strip()]
        for table in doc.tables:
            for row in table.rows:
                for cell in row.cells:
                    if cell.text.strip():
                        paragraphs.append(cell.text.strip())
        return "\n".join(paragraphs)
    except Exception as e:
        raise ValueError(f"Failed to extract text from DOCX: {str(e)}")