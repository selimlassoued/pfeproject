from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.middleware.cors import CORSMiddleware

from app.extractor import extract_text
from app.nlp_parser import parse_cv
from app.models import CvAnalysisResult

MAX_FILE_SIZE_MB = 10
MAX_FILE_SIZE_BYTES = MAX_FILE_SIZE_MB * 1024 * 1024

app = FastAPI(
    title="CV Parser Service",
    description="NLP-powered CV analysis microservice for HireAI",
    version="1.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
def health():
    return {"status": "UP", "service": "cv-parser-service"}


@app.post("/api/cv-parser/analyze", response_model=CvAnalysisResult)
async def analyze_cv(
    application_id: str = Form(...),
    filename: str = Form(...),
    file: UploadFile = File(...),
):
    """
    Receive a CV file (PDF or DOCX) + application_id,
    extract text, run NLP pipeline, return structured data.
    """
    try:
        file_bytes = await file.read()

        if len(file_bytes) == 0:
            raise HTTPException(status_code=400, detail="Empty file received")

        if len(file_bytes) > MAX_FILE_SIZE_BYTES:
            raise HTTPException(
                status_code=413,
                detail=f"File too large. Maximum size is {MAX_FILE_SIZE_MB} MB",
            )

        # Step 1: Extract raw text (includes line-break repair for PDFs)
        raw_text = extract_text(file_bytes, filename)

        if not raw_text or len(raw_text.strip()) < 50:
            raise HTTPException(
                status_code=422,
                detail="Could not extract sufficient text from the CV",
            )

        # Step 2: Run NLP pipeline
        result = parse_cv(raw_text, application_id)

        return result

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Analysis failed: {str(e)}")


@app.post("/api/cv-parser/extract-text")
async def extract_text_only(
    filename: str = Form(...),
    file: UploadFile = File(...),
):
    """Debug endpoint — returns raw extracted text only."""
    file_bytes = await file.read()
    text = extract_text(file_bytes, filename)
    return {"text": text, "length": len(text)}