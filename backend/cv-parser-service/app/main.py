from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from typing import Optional
from dotenv import load_dotenv

load_dotenv()  # loads GITHUB_TOKEN and other vars from .env

from app.extractor import extract_text
from app.nlp_parser import parse_cv
from app.models import CvAnalysisResult, SemanticMatchRequest, SemanticMatchResult
from app.semantic_matcher import match_job_to_cv, calibrate_thresholds

MAX_FILE_SIZE_MB    = 10
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
    github_url: Optional[str] = Form(None),
):
    """
    Parse a CV file and return structured data including GitHub enrichment.

    CV parsing and GitHub fetching run in PARALLEL:
    - 5 LLM calls run sequentially (~22s)
    - GitHub API calls run in a background thread at the same time
    - Result includes github_profile when both are done

    github_url: optional — provided from the application form.
                If not provided, GitHub URL is extracted from the CV text.
                GitHub enrichment only runs for candidates with < 2 years experience.
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

        raw_text = extract_text(file_bytes, filename)

        if not raw_text or len(raw_text.strip()) < 50:
            raise HTTPException(
                status_code=422,
                detail="Could not extract sufficient text from the CV",
            )

        result = parse_cv(raw_text, application_id, github_url=github_url)
        return result

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Analysis failed: {str(e)}")


@app.get("/api/cv-parser/calibrate-thresholds")
async def calibrate():
    """
    Run known tech pairs through the embedding model and return recommended thresholds.
    Use this to calibrate SKILL_SEM_THRESHOLD and SKILL_SEM_PARTIAL_THRESHOLD.
    """
    return calibrate_thresholds()


@app.get("/api/cv-parser/skill-similarity")
async def skill_similarity(a: str, b: str):
    """Debug endpoint — returns the embedding cosine similarity between two skill terms.
    Use this to calibrate the matching thresholds."""
    from app.semantic_matcher import _embed, _cosine, _normalize
    vec_a = _embed(_normalize(a))
    vec_b = _embed(_normalize(b))
    sim = _cosine(vec_a, vec_b)
    return {"skill_a": a, "skill_b": b, "cosine_similarity": round(sim, 4), "score": round(sim * 100)}


@app.post("/api/cv-parser/extract-text")
async def extract_text_only(
    filename: str = Form(...),
    file: UploadFile = File(...),
):
    """Debug endpoint — returns raw extracted text only."""
    file_bytes = await file.read()
    text = extract_text(file_bytes, filename)
    return {"text": text, "length": len(text)}


@app.post("/api/cv-parser/match", response_model=SemanticMatchResult)
async def semantic_match(request: SemanticMatchRequest):
    """Compute a job-to-CV fit score from job data and an already parsed CV."""
    try:
        return match_job_to_cv(request)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Semantic matching failed: {str(e)}")