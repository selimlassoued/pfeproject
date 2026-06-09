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


@app.post("/api/cv-parser/skills/same")
async def are_skills_same(payload: dict):
    """LLM tiebreaker for the resolve endpoint's 0.90-0.95 gray band.

    The catalog hits this when two skill names are embedding-similar enough to
    plausibly be the same (typo, synonym, abbreviation) but not similar enough
    for silent auto-merge. The LLM (qwen2.5:7b) decides — much more accurate
    than fixed cosine thresholds for short-string disambiguation.

    Reply shape: {"same": bool, "reason": str}.
    """
    a = (payload or {}).get("a", "")
    b = (payload or {}).get("b", "")
    if not isinstance(a, str) or not isinstance(b, str) or not a.strip() or not b.strip():
        raise HTTPException(status_code=400, detail="a and b are required non-empty strings")

    import json as _json
    from app.semantic_matcher import ollama_client, ANALYSIS_MODEL

    prompt = (
        "You disambiguate skill names in a recruitment database.\n\n"
        f"Skill A: \"{a.strip()}\"\n"
        f"Skill B: \"{b.strip()}\"\n\n"
        "Are these the SAME technical skill — one being a typo, abbreviation, or formatting "
        "variant of the other — or are they DIFFERENT skills (different languages, frameworks, "
        "or technologies)?\n\n"
        "Reply with STRICT JSON: {\"same\": true|false, \"reason\": \"<one short sentence>\"}"
    )

    try:
        resp = ollama_client.chat(
            model=ANALYSIS_MODEL,
            format="json",
            messages=[{"role": "user", "content": prompt}],
            options={"temperature": 0.0},
        )
        content = resp.get("message", {}).get("content") if isinstance(resp, dict) \
                  else getattr(resp.message, "content", "")
        data = _json.loads(content) if content else {}
        same = bool(data.get("same", False))
        reason = str(data.get("reason", "")).strip()[:200]
        return {"same": same, "reason": reason, "model": ANALYSIS_MODEL}
    except Exception as e:
        # Fail conservative: treat as different so we never silently merge on error.
        return {"same": False, "reason": f"llm error: {e.__class__.__name__}", "model": ANALYSIS_MODEL}


@app.post("/api/cv-parser/skills/suggest-synonyms")
async def suggest_synonyms(payload: dict):
    """Generate candidate synonyms / aliases for a skill name.

    Primary use: SOFT skills (Leadership, Communication, ...) where CV
    phrasing varies wildly and the embedding model (nomic-embed-text) caps
    out around cosine 0.70 on abstract concepts. The recruiter adds the
    canonical name once, this endpoint proposes paraphrases, and the
    /match-soft-skill resolver does an exact-string short-circuit against
    the curated synonym list before paying the embedding round-trip.

    The recruiter accepts, edits, or rejects the suggestions before saving:
    we never auto-commit to the catalog.

    Request:  { "name": "Leadership", "type": "SOFT" }
    Response: { "synonyms": ["team lead", "people management", ...] }
    """
    name = (payload or {}).get("name", "")
    if not isinstance(name, str) or not name.strip():
        raise HTTPException(status_code=400, detail="name is required")
    skill_type = (payload or {}).get("type", "SOFT")
    if not isinstance(skill_type, str) or skill_type.upper() not in ("HARD", "SOFT"):
        skill_type = "SOFT"

    import json as _json
    from app.semantic_matcher import ollama_client, ANALYSIS_MODEL

    # Examples explicitly bias the LLM toward the kinds of paraphrases that
    # actually show up in CV prose ("team lead", "communicates well") rather
    # than dictionary-style synonyms ("guidance" for "leadership").
    if skill_type.upper() == "SOFT":
        examples = (
            'Examples of CV-style paraphrases:\n'
            '- "Leadership"   -> ["team lead", "people management", "directing teams", "led a team", "managing people"]\n'
            '- "Communication" -> ["strong communicator", "verbal skills", "communicates well", "written communication", "interpersonal skills"]\n'
            '- "Teamwork"     -> ["team player", "collaborates well", "cross-functional collaboration", "works well in teams"]\n'
        )
    else:
        examples = (
            'Examples of variant spellings:\n'
            '- "Node.js" -> ["nodejs", "node js", "node"]\n'
            '- "Next.js" -> ["nextjs", "next js"]\n'
            '- "PostgreSQL" -> ["postgres", "psql", "postgre sql"]\n'
        )

    prompt = (
        f"Generate paraphrases for the skill \"{name.strip()}\" "
        f"(type: {skill_type.upper()}).\n\n"
        f"{examples}\n"
        "Return 5-8 lowercase paraphrases as they would appear in a candidate's "
        "CV — NOT dictionary synonyms, NOT definitions. Each one must be a phrase "
        "a person would actually write about themselves. Skip anything that's a "
        "different skill, a job title, or a company name.\n\n"
        "Reply with STRICT JSON: {\"synonyms\": [\"phrase1\", \"phrase2\", ...]}"
    )

    try:
        resp = ollama_client.chat(
            model=ANALYSIS_MODEL,
            format="json",
            messages=[{"role": "user", "content": prompt}],
            options={"temperature": 0.2},
        )
        content = resp.get("message", {}).get("content") if isinstance(resp, dict) \
                  else getattr(resp.message, "content", "")
        data = _json.loads(content) if content else {}
        raw = data.get("synonyms", [])
        out: list[str] = []
        seen: set[str] = set()
        canonical_lc = name.strip().lower()
        if isinstance(raw, list):
            for item in raw:
                if not isinstance(item, str):
                    continue
                norm = item.strip().lower()
                if not norm or norm == canonical_lc or norm in seen:
                    continue
                if len(norm) > 80:
                    continue
                seen.add(norm)
                out.append(norm)
                if len(out) >= 8:
                    break
        return {"synonyms": out, "model": ANALYSIS_MODEL}
    except Exception as e:
        return {"synonyms": [], "error": f"{e.__class__.__name__}", "model": ANALYSIS_MODEL}


@app.post("/api/cv-parser/skill-classify")
async def classify_skill(payload: dict):
    """Validate AND properly format a skill name claimed to be HARD or SOFT.

    One LLM call does four jobs:
      1. Validate the input is a real skill (not a job title, company, garbage)
      2. Verify the caller's expected_type matches what the LLM thinks
      3. Produce the canonical display name
      4. Identify what other skills this one implies

    The caller ALWAYS knows what type they expect at call time:
      - Job-requirement form: recruiter picked HARD/SOFT category before typing
      - CV parser: extracts hard skills and soft skills into separate fields
      - Preferences page: chip grids are split by type
    So `expected_type` should always be provided. The LLM biases toward agreement
    and only overrides when highly confident the caller made a mistake (e.g.,
    a recruiter accidentally typed "Leadership" in a HARD requirement section).

    Request shape:
      { "text": "...", "expected_type": "HARD" | "SOFT" }

    Reply shape:
      {
        "type":         "HARD" | "SOFT" | "INVALID",
        "display_name": "<canonical casing>",
        "implies":      [<canonical lowercase skill names this is built on>],
        "reason":       "<one short sentence>"
      }
    """
    text = (payload or {}).get("text", "")
    if not isinstance(text, str) or not text.strip():
        raise HTTPException(status_code=400, detail="text is required")

    raw_expected = (payload or {}).get("expected_type", "")
    expected_type = ""
    if isinstance(raw_expected, str):
        normed = raw_expected.strip().upper()
        if normed in ("HARD", "SOFT"):
            expected_type = normed

    import json as _json
    from app.semantic_matcher import ollama_client, ANALYSIS_MODEL

    cleaned = " ".join(text.strip().split())

    expected_block = (
        (f"CALLER'S CLAIM: this was entered as a {expected_type} skill. "
         f"Bias toward agreeing - only override to a different type if you are HIGHLY "
         f"confident the caller made a mistake (e.g. they typed 'Leadership' into a "
         f"HARD-skill requirement section). Otherwise return type={expected_type}.\n\n")
        if expected_type else
        "CONTEXT: this was entered as a skill on a job posting or candidate profile.\n\n"
    )

    prompt = (
        "You validate AND properly format skill names for a recruitment database.\n\n"
        f"Skill name: \"{cleaned}\"\n\n"
        + expected_block +
        "Do THREE things in one response.\n\n"
        "=== 1) CLASSIFY ===\n\n"
        "HARD = technical skill: programming language, framework, library, tool, technical\n"
        "       concept, or domain knowledge. When a bare short word could be a known\n"
        "       tech framework (\"node\", \"express\", \"next\", \"react\", \"vue\", \"spring\",\n"
        "       \"django\", \"rails\", \"r\", \"c\", \"go\", \"rust\", \"astro\", \"bun\",\n"
        "       \"svelte\", \"remix\", \"deno\"), classify as HARD.\n\n"
        "SOFT = universal interpersonal or behavioral ability. Recognize ALL of these as SOFT:\n"
        "       communication, teamwork, leadership, mentoring, mentorship, collaboration,\n"
        "       problem solving, critical thinking, creativity, creative thinking,\n"
        "       attention to detail, time management, organization, planning,\n"
        "       adaptability, flexibility, resilience, work ethic, initiative,\n"
        "       emotional intelligence, empathy, active listening, conflict resolution,\n"
        "       cross-cultural collaboration, public speaking, presentation skills,\n"
        "       writing skills, decision making, analytical thinking, negotiation,\n"
        "       customer service, stakeholder management, project management methodology.\n\n"
        "INVALID = NOT a skill. Strict examples:\n"
        "   - Job titles: \"software engineer\", \"director\", \"senior developer\", \"ceo\"\n"
        "   - Company names alone: \"microsoft\", \"google\", \"oracle corp\"\n"
        "   - Random text: \"xyz123\", \"asdfgh\", \"hello world\"\n"
        "   - Colors, body parts, foods, places, animals\n"
        "   - Sentences or descriptions: \"i am good at...\"\n"
        "   - Pure punctuation or single random letters: \".\", \"-\"\n\n"
        "=== 2) DISPLAY_NAME ===\n\n"
        "The canonical capitalization used by the community. Examples:\n"
        "   JavaScript, TypeScript, iOS, macOS, GraphQL, scikit-learn (lowercase),\n"
        "   .NET, C++, C#, jQuery, PostgreSQL, MySQL, MongoDB, Spring Boot,\n"
        "   Node.js, Next.js, Express.js, React, Vue.js, Angular\n\n"
        "Rules:\n"
        "   - For bare framework names use conventional form: \"node\" -> \"Node.js\"\n"
        "   - Title-case ordinary multi-word skills: \"data analysis\" -> \"Data Analysis\"\n"
        "   - Soft skills are simple title case: \"communication\" -> \"Communication\"\n"
        "   - NEVER add parenthetical explanations (no \"(CRM)\", no \"(framework)\")\n"
        "   - NEVER add words not in the input (no \"engineering\", no \"developer\")\n"
        "   - Preserve special characters (dots, hyphens, slashes, plus signs)\n"
        "   - When INVALID, return a tidied version of the input as-is\n\n"
        "=== 3) IMPLIES ===\n\n"
        "A list of OTHER skills (canonical lowercase form) that this skill is built on\n"
        "or strongly implies the candidate knows. Used by the matcher to credit candidates\n"
        "for related skills they may not have listed explicitly.\n\n"
        "Examples:\n"
        "   \"node\"        -> implies [\"javascript\"]\n"
        "   \"next\"        -> implies [\"react\", \"javascript\"]\n"
        "   \"nuxt\"        -> implies [\"vue\", \"javascript\"]\n"
        "   \"spring boot\" -> implies [\"java\"]\n"
        "   \"django\"      -> implies [\"python\"]\n"
        "   \"rails\"       -> implies [\"ruby\"]\n"
        "   \"tensorflow\"  -> implies [\"python\"]\n"
        "   \"react\"       -> implies [\"javascript\"]\n"
        "   \"vue\"         -> implies [\"javascript\"]\n"
        "   \"typescript\"  -> implies [\"javascript\"]\n"
        "   \"swift\"       -> implies []     (no parent technology)\n"
        "   \"java\"        -> implies []     (base language)\n"
        "   \"communication\" -> implies []   (soft skills have no implies)\n"
        "   \"docker\"      -> implies [\"linux\"]\n"
        "   \"kubernetes\"  -> implies [\"docker\", \"linux\"]\n\n"
        "Rules:\n"
        "   - 0 to 3 entries\n"
        "   - Lowercase canonical names only (no display casing)\n"
        "   - Use bare names without .js suffix (\"react\" not \"react.js\")\n"
        "   - SOFT and INVALID skills: return empty list []\n"
        "   - Base languages (Java, Python, Go, Rust, C): return empty list []\n"
        "   - Only include skills you are highly confident the candidate uses if they use this skill\n\n"
        "Reply with STRICT JSON: "
        "{\"type\": \"HARD\"|\"SOFT\"|\"INVALID\", "
        "\"display_name\": \"<name>\", "
        "\"implies\": [...], "
        "\"reason\": \"<one short sentence>\"}"
    )

    try:
        resp = ollama_client.chat(
            model=ANALYSIS_MODEL,
            format="json",
            messages=[{"role": "user", "content": prompt}],
            options={"temperature": 0.0},
        )
        content = resp.get("message", {}).get("content") if isinstance(resp, dict) \
                  else getattr(resp.message, "content", "")
        data = _json.loads(content) if content else {}
        raw_type = str(data.get("type", "")).strip().upper()
        if raw_type not in ("HARD", "SOFT", "INVALID"):
            raw_type = "INVALID"
        display = str(data.get("display_name", "")).strip()
        if not display:
            # LLM forgot the display name - fall back to the cleaned input.
            display = cleaned
        reason = str(data.get("reason", "")).strip()[:200]
        # Parse implies: list of canonical lowercase skill names.
        raw_implies = data.get("implies", [])
        implies: list[str] = []
        if isinstance(raw_implies, list):
            for item in raw_implies:
                if isinstance(item, str):
                    cleaned_item = item.strip().lower()
                    # Defensive: drop empty entries, self-references, and obvious garbage.
                    if cleaned_item and cleaned_item != cleaned and len(cleaned_item) <= 60:
                        implies.append(cleaned_item)
                if len(implies) >= 3:  # cap at 3 entries
                    break
        # SOFT and INVALID skills should have no implies, even if the LLM returned some.
        if raw_type != "HARD":
            implies = []
        return {
            "type": raw_type,
            "display_name": display,
            "implies": implies,
            "reason": reason,
            "model": ANALYSIS_MODEL,
        }
    except Exception as e:
        # Fail-open: if the LLM errors out, treat the input as a valid HARD skill
        # and return a title-cased fallback display name so the catalog still gets
        # a reasonable entry.
        fallback = " ".join(w[:1].upper() + w[1:] if w else w for w in cleaned.split())
        return {
            "type": "HARD",
            "display_name": fallback,
            "implies": [],
            "reason": f"llm unavailable: {e.__class__.__name__}; bypassing validation",
            "model": ANALYSIS_MODEL,
        }


@app.post("/api/cv-parser/embed-text")
async def embed_text(payload: dict):
    """Embed an arbitrary string and return the 768-dim vector.

    Consumed by application-microservice's catalog-backfill flow: when the
    catalog has rows with NULL embedding (legacy entries added before the
    pgvector column existed), the Java side POSTs each skill's text here,
    receives the vector, and persists it via PUT /intel.

    Reuses semantic_matcher._embed which is the same Ollama call path the
    matcher uses for live scoring — so the stored vector is identical to
    what the matcher would have produced lazily.
    """
    from app.semantic_matcher import _embed, EMBEDDING_MODEL
    text = (payload or {}).get("text", "")
    if not isinstance(text, str) or not text.strip():
        raise HTTPException(status_code=400, detail="text is required")
    vec = _embed(text.strip())
    if not vec or len(vec) != 768:
        raise HTTPException(status_code=502, detail="Ollama returned empty or wrong-dim vector")
    return {"embedding": vec, "model": EMBEDDING_MODEL, "dim": len(vec)}


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
    name:                  str            # canonical lowercase key
    display_name:          str            # pretty form for UI
    first_seen_at:         Optional[str]  # ISO 8601 of earliest job mentioning it
    current_demand_count:  int            # # of PUBLISHED jobs currently mentioning it
    lifetime_demand_count: int = 0        # # of non-DRAFT jobs that EVER mentioned it (never decreases)
    source:                str = "EXTRACTED"
    domains:               list[str] = [] # all distinct job-domains that mentioned this skill

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
      • Skip DRAFT - those are private working drafts and may contain typos
      • Skills come from category=SKILL/TECHNICAL/TECHNOLOGY/COMPÉTENCE
      • Languages come from category=LANGUAGE/LANGUE/LANGUAGES, alias-matched
        against LANGUAGE_ALIASES so "Italien" and "Italian" both surface as
        canonical "Italian"

    For each item we report:
      • first_seen_at         - earliest job createdAt that mentions it (drives NEW badge)
      • current_demand_count  - number of PUBLISHED jobs currently mentioning it
                                (drives ranking and the "in-demand" visual)
      • lifetime_demand_count - number of non-DRAFT jobs that EVER mentioned it,
                                never decreases when a job closes
                                (drives Preferences VISIBILITY: skills with
                                lifetime > 0 have at some point been required by
                                a recruiter and earn a slot on the chip grid;
                                skills with lifetime = 0 only appear on candidate
                                CVs and stay searchable but hidden from the grid)

    Frontend Preferences logic:
      filter: lifetime_demand_count > 0
      sort:   current_demand_count DESC, lifetime_demand_count DESC, name ASC
      opacity by current_demand_count: high=strong, low=faded, dormant=very faded
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

    # Counters per skill / language:
    #
    #   first_seen        - earliest job createdAt that mentioned it (drives "NEW" badge)
    #   demand (current)  - # of PUBLISHED jobs currently mentioning it (drives ranking)
    #   lifetime          - # of ANY non-DRAFT job that EVER mentioned it (drives visibility
    #                       on Preferences: lifetime > 0 means "a recruiter has at any point
    #                       asked for this skill" - skills only seen in candidate CVs never
    #                       get bumped here, so they stay out of the chip grid)
    #   domains           - distinct job-domains that mentioned the skill (filters)
    skill_first_seen: dict[str, str]      = {}
    skill_display:    dict[str, str]      = {}
    skill_demand:     dict[str, int]      = {}
    skill_lifetime:   dict[str, int]      = {}
    skill_domains:    dict[str, set[str]] = {}
    lang_first_seen:  dict[str, str]      = {}
    lang_demand:      dict[str, int]      = {}
    lang_lifetime:    dict[str, int]      = {}
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
                    # Lifetime: every non-DRAFT job counts, never decreases.
                    skill_lifetime[key] = skill_lifetime.get(key, 0) + 1
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
                        lang_lifetime[canonical] = lang_lifetime.get(canonical, 0) + 1
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
            lifetime_demand_count=skill_lifetime.get(key, 0),
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
            lifetime_demand_count=lang_lifetime.get(name, 0),
            source="EXTRACTED",
            domains=sorted(lang_domains.get(name, set())),
        )
        for name in sorted(lang_first_seen.keys())
    ]
    return ExtractCatalogResponse(skills=skills, languages=languages)