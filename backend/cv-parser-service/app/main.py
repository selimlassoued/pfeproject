import re
from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from typing import Optional
from dotenv import load_dotenv

load_dotenv()  # loads GITHUB_TOKEN and other vars from .env

from app.extractor import extract_text
from app.nlp_parser import parse_cv
from app.models import CvAnalysisResult, SemanticMatchRequest, SemanticMatchResult
from app.semantic_matcher import match_job_to_cv, calibrate_thresholds
from app.github_enricher import enrich_from_github

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


@app.get("/api/cv-parser/debug/github/{username}")
def debug_github(username: str):
    """Debug endpoint: run GitHub enrichment for one username and return the raw result.
    Use to verify tech_stats, framework detection, and per-tech years without
    going through a full CV upload. Example:
      GET http://localhost:8001/api/cv-parser/debug/github/selimlassoued
    """
    result = enrich_from_github(f"https://github.com/{username}")
    if not result:
        raise HTTPException(status_code=404, detail=f"GitHub user not found or unreachable: {username}")
    return {
        "username":            result.get("username"),
        "github_score":        result.get("github_score"),
        "own_repos_count":     result.get("own_repos_count"),
        "tech_stats":          result.get("tech_stats", {}),
        "all_technologies":    result.get("all_technologies", []),
        "all_repo_frameworks": result.get("all_repo_frameworks", []),
        "top_langs_lower":     result.get("top_langs_lower", []),
    }


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


# ── Job recommendation ranking ────────────────────────────────────────────────
from pydantic import BaseModel as _BaseModel

class JobRankItem(_BaseModel):
    id: str
    text: str
    # Structured signals the candidate side can send so the ranker doesn't
    # have to scrape them out of the free-form text. All optional for back-compat.
    work_arrangement: Optional[str] = None      # ON_SITE / HYBRID / REMOTE
    employment_type:  Optional[str] = None      # FULL_TIME / PART_TIME / …
    requirement_text: Optional[str] = None      # concatenated requirement descriptions

class RankJobsRequest(_BaseModel):
    candidate_text: str
    jobs: list[JobRankItem]
    # Optional structured candidate signals — when sent, they sharpen the
    # ranking with skill overlap + preference fit on top of the embedding sim.
    candidate_hard_skills: list[str] = []
    # Multi-select preferences. Empty list (or list covering every possible
    # option) is treated as "no preference" — every job stays at pref_fit=1.0.
    candidate_preferred_work_arrangements: list[str] = []
    candidate_preferred_job_types:         list[str] = []
    # Back-compat: older clients may still send the singular form. We coalesce
    # them into the plural lists at request handling time.
    candidate_preferred_work_arrangement:  Optional[str] = None
    candidate_preferred_job_type:          Optional[str] = None

class JobRankResult(_BaseModel):
    id: str
    score: float

class RankJobsResponse(_BaseModel):
    results: list[JobRankResult]


def _normalize_skill_token(s: str) -> str:
    return re.sub(r"[^a-z0-9+#.\-/ ]", "", (s or "").lower()).strip() if s else ""


@app.post("/api/cv-parser/rank-jobs", response_model=RankJobsResponse)
async def rank_jobs(req: RankJobsRequest):
    """
    Rank open jobs for a logged-in candidate. The score combines three signals:

      • semantic_sim   — embedding cosine between candidate profile and job text,
                         rescaled through the proper document-window normalizer
                         (raw doc-vs-doc cosines run ~0.55-0.85, which the old
                         `(cos+1)/2` formula squashed into a meaningless 0.78-0.92
                         band — that's why every job used to look "Strong match").
      • skill_overlap  — fraction of the candidate's declared hard skills that
                         appear (as substrings) in the job requirements/text.
                         Gives a meaningful nudge toward jobs in the candidate's
                         actual tech stack.
      • preference_fit — multiplier ≤ 1.0 when the job's work_arrangement or
                         employment_type contradicts the candidate's stated
                         preference. Jobs that match preferences stay at 1.0.

    Final = (0.55 × semantic_sim + 0.45 × skill_overlap) × preference_fit
    """
    from app.semantic_matcher import _embed, _cosine, _normalize_cosine

    candidate_vec = _embed(req.candidate_text)
    if not candidate_vec:
        return RankJobsResponse(results=[JobRankResult(id=j.id, score=0.0) for j in req.jobs])

    # Normalize candidate skills once for substring lookup against job text.
    cand_skills = {_normalize_skill_token(s) for s in (req.candidate_hard_skills or []) if s}
    cand_skills = {s for s in cand_skills if len(s) >= 2}
    n_skills = len(cand_skills)

    # Normalize multi-select preferences into uppercase sets. We accept both
    # the new plural fields (preferred lists) and the legacy singular fields
    # (back-compat with older clients) and merge them.
    ARRANGEMENT_OPTIONS = {"ON_SITE", "HYBRID", "REMOTE"}
    JOB_TYPE_OPTIONS    = {"FULL_TIME", "PART_TIME", "CONTRACT",
                           "INTERNSHIP", "ALTERNANCE"}

    def _to_set(plural: list[str], singular: Optional[str]) -> set[str]:
        s: set[str] = {x.upper().strip() for x in (plural or []) if x and x.strip()}
        if singular and singular.strip():
            s.add(singular.upper().strip())
        return s

    cand_arrangements = _to_set(
        req.candidate_preferred_work_arrangements, req.candidate_preferred_work_arrangement)
    cand_job_types    = _to_set(
        req.candidate_preferred_job_types,         req.candidate_preferred_job_type)

    # Selecting every option is the same as selecting none — both mean "I'm
    # open to anything". Clear the set so the penalty branch below short-
    # circuits on `if cand_arrangements:` etc.
    if cand_arrangements and cand_arrangements >= ARRANGEMENT_OPTIONS:
        cand_arrangements = set()
    if cand_job_types and cand_job_types >= JOB_TYPE_OPTIONS:
        cand_job_types = set()

    results: list[JobRankResult] = []
    for job in req.jobs:
        # ── 1. Semantic similarity (proper rescaling) ─────────────────────
        job_vec     = _embed(job.text)
        raw_cos     = _cosine(candidate_vec, job_vec)
        semantic    = _normalize_cosine(raw_cos)  # [0, 1]

        # ── 2. Skill overlap ──────────────────────────────────────────────
        skill_overlap = 0.0
        if n_skills > 0:
            haystack = (job.requirement_text or job.text or "").lower()
            hits = sum(1 for s in cand_skills if s in haystack)
            skill_overlap = hits / n_skills

        # ── 3. Preference fit ─────────────────────────────────────────────
        # Soft penalties (multipliers, not zero) — the candidate may still
        # want to see a near-miss arrangement, we just rank it lower. With
        # multi-select, a job whose arrangement is in the candidate's chosen
        # set is a full match (1.0); only jobs OUTSIDE the chosen set get
        # the penalty. Empty set = no preference = no penalty.
        pref_fit = 1.0
        if cand_arrangements and job.work_arrangement \
                and job.work_arrangement.upper() not in cand_arrangements:
            pref_fit *= 0.75
        if cand_job_types and job.employment_type \
                and job.employment_type.upper() not in cand_job_types:
            pref_fit *= 0.85

        base  = 0.55 * semantic + 0.45 * skill_overlap
        score = round(max(0.0, min(base * pref_fit, 1.0)), 4)
        results.append(JobRankResult(id=job.id, score=score))

    results.sort(key=lambda r: r.score, reverse=True)
    return RankJobsResponse(results=results)


# ── Catalog extraction ───────────────────────────────────────────────────────
# Feeds the candidate-side chip grid (Preferences + Onboarding) with the
# universe of skills + languages that have been mentioned in real job postings.
# Frontend posts the list of jobs (with their requirements + createdAt) and
# this endpoint returns a deduped catalog with timestamps.
#
# Why server-side: the skill parser (_extract_raw_skills + _MULTI_WORD_SKILLS)
# lives here and handles edge cases the frontend's JS regex would miss —
# multi-word phrases ("Spring Boot", "linux command line"), year filtering
# ("5+ years"), stopwords, etc.

class CatalogJobRequirement(_BaseModel):
    category:    Optional[str] = None
    description: Optional[str] = None
    skill_level: Optional[str] = None

class CatalogJob(_BaseModel):
    id:           str
    created_at:   Optional[str] = None     # ISO 8601; falls back to "epoch" if missing
    job_status:   Optional[str] = None     # PUBLISHED / CLOSED / DRAFT
    domain:       Optional[str] = None     # SOFTWARE_ENGINEERING / FINANCE_BANKING / ...
    requirements: list[CatalogJobRequirement] = []

class ExtractCatalogRequest(_BaseModel):
    jobs: list[CatalogJob] = []

class CatalogItem(_BaseModel):
    name:                 str            # canonical lowercase key
    display_name:         str            # pretty form for UI
    first_seen_at:        Optional[str]  # ISO 8601 of earliest job mentioning it
    current_demand_count: int            # # of PUBLISHED jobs currently mentioning it
    source:               str = "EXTRACTED"
    domains:              list[str] = [] # all distinct job-domains that mentioned this skill

class ExtractCatalogResponse(_BaseModel):
    skills:    list[CatalogItem]
    languages: list[CatalogItem]


def _parse_iso(ts: Optional[str]) -> str:
    """Defensive ISO-8601 normalization — return the original or '1970-…'."""
    if not ts:
        return "1970-01-01T00:00:00Z"
    return ts


@app.post("/api/cv-parser/extract-catalog", response_model=ExtractCatalogResponse)
async def extract_catalog(req: ExtractCatalogRequest):
    """
    Extract the universe of skills + languages from a batch of jobs.

    Scan rule (matches our agreed design):
      • Include PUBLISHED and CLOSED jobs in the catalog (history persists)
      • Skip DRAFT — those are private working drafts and may contain typos
      • Skills come from category=SKILL/TECHNICAL/TECHNOLOGY/COMPÉTENCE
      • Languages come from category=LANGUAGE/LANGUE/LANGUAGES, alias-matched
        against LANGUAGE_ALIASES so "Italien" and "Italian" both surface as
        canonical "Italian"

    For each item we report:
      • first_seen_at — earliest job createdAt that mentions it (drives ⭐NEW)
      • current_demand_count — number of PUBLISHED jobs currently mentioning
        it (drives 🔥 in-demand)
    """
    # Lazy imports — these modules are heavy and we want them out of the cold
    # path of unrelated endpoints.
    from app.semantic_matcher import _extract_raw_skills, _normalize

    # Hand-maintained alias map (mirrors the frontend's LANGUAGE_ALIASES so
    # spelling variants in job text get canonicalized consistently). Keys are
    # the canonical English display names.
    LANGUAGE_ALIASES: dict[str, list[str]] = {
        "Arabic":     ["arabic", "arabe"],
        "French":     ["french", "francais", "français", "fr"],
        "English":    ["english", "anglais", "en"],
        "German":     ["german", "allemand", "deutsch", "de"],
        "Spanish":    ["spanish", "espagnol", "español", "espanol", "castellano", "es"],
        "Italian":    ["italian", "italien", "italiano", "it"],
        "Portuguese": ["portuguese", "portugais", "português", "portugues", "pt"],
        "Dutch":      ["dutch", "néerlandais", "neerlandais", "nederlands"],
        "Polish":     ["polish", "polonais", "polski"],
        "Russian":    ["russian", "russe"],
        "Turkish":    ["turkish", "turc", "türkçe", "turkce"],
        "Swedish":    ["swedish", "suédois", "suedois", "svenska"],
        "Norwegian":  ["norwegian", "norvégien", "norvegien", "norsk"],
        "Mandarin":   ["mandarin", "chinese", "chinois"],
        "Japanese":   ["japanese", "japonais"],
        "Korean":     ["korean", "coréen", "coreen"],
        "Hindi":      ["hindi"],
        "Urdu":       ["urdu"],
    }

    # Two passes: build firstSeenAt + currentDemandCount per name.
    # firstSeenAt uses ALL non-draft jobs (so closed jobs keep their history).
    # currentDemandCount uses only PUBLISHED jobs (so closed jobs lose 🔥).
    # domains accumulates every distinct domain whose jobs mentioned this
    # skill — drives the candidate-side per-domain chip-grid filter.
    skill_first_seen: dict[str, str] = {}
    skill_display:    dict[str, str] = {}
    skill_demand:     dict[str, int] = {}
    skill_domains:    dict[str, set[str]] = {}
    lang_first_seen:  dict[str, str] = {}
    lang_demand:      dict[str, int] = {}
    lang_domains:     dict[str, set[str]] = {}

    SKILL_CATS = {"SKILL", "TECHNICAL", "TECHNOLOGY", "COMPÉTENCE"}
    LANG_CATS  = {"LANGUAGE", "LANGUE", "LANGUAGES"}

    for job in req.jobs:
        status = (job.job_status or "").upper()
        if status == "DRAFT":
            continue  # don't leak private working drafts into the catalog
        is_published = status == "PUBLISHED"
        job_ts = _parse_iso(job.created_at)
        job_domain = (job.domain or "").strip().upper() or None

        for r in (job.requirements or []):
            cat = (r.category or "").upper()
            text = (r.description or "").strip()
            if not text:
                continue

            if cat in SKILL_CATS:
                tokens = _extract_raw_skills(_normalize(text))
                for tok in tokens:
                    key = tok.lower().strip()
                    if not key:
                        continue
                    prev = skill_first_seen.get(key)
                    if prev is None or job_ts < prev:
                        skill_first_seen[key] = job_ts
                    # Display name: prefer first-cased form we saw
                    if key not in skill_display:
                        skill_display[key] = tok.title() if tok.islower() else tok
                    if is_published:
                        skill_demand[key] = skill_demand.get(key, 0) + 1
                    if job_domain:
                        skill_domains.setdefault(key, set()).add(job_domain)

            elif cat in LANG_CATS:
                haystack = text.lower()
                for canonical, aliases in LANGUAGE_ALIASES.items():
                    if any(a in haystack for a in aliases):
                        prev = lang_first_seen.get(canonical)
                        if prev is None or job_ts < prev:
                            lang_first_seen[canonical] = job_ts
                        if is_published:
                            lang_demand[canonical] = lang_demand.get(canonical, 0) + 1
                        if job_domain:
                            lang_domains.setdefault(canonical, set()).add(job_domain)

    skills = [
        CatalogItem(
            name=key,
            display_name=skill_display.get(key, key.title()),
            first_seen_at=skill_first_seen.get(key),
            current_demand_count=skill_demand.get(key, 0),
            source="EXTRACTED",
            domains=sorted(skill_domains.get(key, set())),
        )
        for key in sorted(skill_first_seen.keys())
    ]
    languages = [
        CatalogItem(
            name=name.lower(),
            display_name=name,
            first_seen_at=lang_first_seen.get(name),
            current_demand_count=lang_demand.get(name, 0),
            source="EXTRACTED",
            domains=sorted(lang_domains.get(name, set())),
        )
        for name in sorted(lang_first_seen.keys())
    ]
    return ExtractCatalogResponse(skills=skills, languages=languages)