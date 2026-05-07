import math
import os
import re
import json
from typing import Iterable, Optional

import ollama

from app.models import (
    CvAnalysisResult,
    JobRequirementInput,
    SemanticMatchRequest,
    SemanticMatchResult,
)

# ── Config ────────────────────────────────────────────────────────────────────

OLLAMA_HOST      = os.getenv("OLLAMA_HOST", "http://localhost:11434")
EMBEDDING_MODEL  = os.getenv("SEMANTIC_MATCH_EMBED_MODEL", "nomic-embed-text")
ANALYSIS_MODEL   = os.getenv("SEMANTIC_MATCH_ANALYSIS_MODEL", "qwen2.5:7b")

# Cosine similarity threshold for semantic skill synonyms (e.g. React / ReactJS)
# Embedding similarity thresholds for skill matching.
# The model already encodes language-framework relationships (Java/Spring Boot, PHP/Laravel, etc.)
# — lower thresholds let that natural proximity register as partial credit.
# Calibrated against real nomic-embed-text pairs (see /api/cv-parser/calibrate-thresholds)
# matched group min: 81% (spring/spring boot) — unrelated max: 48% (python/excel)
SKILL_SEM_THRESHOLD         = 0.80  # ≥80% → matched (green):  near-synonyms and variants
SKILL_SEM_PARTIAL_THRESHOLD = 0.43  # ≥43% → partial (orange): language/framework family relationships

ollama_client = ollama.Client(host=OLLAMA_HOST)

# ── Education degree hierarchy ─────────────────────────────────────────────────
_DEGREE_LEVELS: dict[str, int] = {
    "phd": 4, "doctorate": 4, "doctor": 4,
    "master": 3, "msc": 3, "mba": 3,
    "bachelor": 2, "licence": 2, "license": 2, "bsc": 2,
    "engineering": 2, "ingénieur": 2, "ingenieur": 2, "licence professionnelle": 2,
    "associate": 1, "dut": 1, "bts": 1, "iset": 1, "technicien": 1,
    "baccalaureate": 0, "baccalauréat": 0, "bac": 0, "high school": 0,
}

# ── Known calibration pairs for threshold tuning ──────────────────────────────
_CALIBRATION_PAIRS: list[tuple[str, str, str]] = [
    # near-synonym → should be "matched"
    ("react",       "reactjs",      "matched"),
    ("spring boot", "spring",       "matched"),
    ("postgresql",  "postgres",     "matched"),
    ("javascript",  "typescript",   "matched"),
    ("node.js",     "nodejs",       "matched"),
    # language → framework → should be "partial"
    ("java",    "spring boot",  "partial"),
    ("python",  "django",       "partial"),
    ("php",     "laravel",      "partial"),
    ("mysql",   "postgresql",   "partial"),
    ("react",   "angular",      "partial"),
    # unrelated → should be "missing"
    ("java",    "photoshop",    "missing"),
    ("python",  "excel",        "missing"),
    ("angular", "machine learning", "missing"),
]

_SENIORITY_ORDER = {"INTERN": 0, "JUNIOR": 1, "MID": 2, "SENIOR": 3}

_STOPWORDS = {
    "and", "or", "with", "the", "for", "plus", "using", "good", "strong",
    "knowledge", "experience", "years", "year", "minimum", "preferred",
    "required", "nice", "have", "must", "ability", "skills", "skill",
    "understanding", "working", "familiarity", "proficiency", "solid",
    "excellent", "proven", "hands", "on", "in", "of", "a", "an",
    "you", "u", "we", "our", "your", "candidate", "applicant",
}

# Qualifier adjectives — describe required level, never a skill name
_QUALIFIERS: dict[str, str] = {
    "basic": "basic", "elementary": "basic", "beginner": "basic", "fundamental": "basic",
    "intermediate": "intermediate", "proficient": "intermediate", "moderate": "intermediate",
    "advanced": "advanced", "expert": "advanced",
}

# Words that follow a qualifier and must also be stripped ("basic knowledge of …")
_QUALIFIER_CONTEXT = {"knowledge", "level", "understanding", "familiarity", "proficiency", "of"}

# Minimum cosine similarity to count as "matched" per qualifier level
_QUALIFIER_THRESHOLDS: dict[str, float] = {
    "basic":        SKILL_SEM_PARTIAL_THRESHOLD,  # 0.43 — any mention is enough
    "intermediate": 0.65,                          # needs real evidence
    "advanced":     0.85,                          # needs strong multi-source evidence
    "any":          SKILL_SEM_THRESHOLD,           # 0.80 — default (no qualifier)
}

_OR_SPLIT_RE  = re.compile(r"\s+or\s+",  re.IGNORECASE)
_AND_SPLIT_RE = re.compile(r"\s+and\s+", re.IGNORECASE)

# Phrases containing these words are education/HR requirements, not technical skills
_EDUCATION_KEYWORDS = {
    "bachelor", "master", "licence", "license", "degree", "diploma",
    "phd", "bsc", "msc", "mba", "bac", "formation", "graduate",
    "university", "school", "engineering", "certification",
}

# Known multi-word technical skills that must stay together
_MULTI_WORD_SKILLS = {
    "spring boot", "machine learning", "deep learning", "natural language",
    "computer vision", "data science", "big data", "node.js", "react.js",
    "vue.js", "angular.js", "rest api", "web services", "agile methodology",
    "ci/cd", "test driven", "object oriented", "design patterns",
}

_SPLIT_RE = re.compile(r"[,;/|()\n]+")



# ── Text normalization ────────────────────────────────────────────────────────

def _normalize(text: Optional[str]) -> str:
    if not text:
        return ""
    s = text.strip().lower()
    s = s.replace("springboot", "spring boot")
    s = s.replace("nodejs", "node.js")
    s = s.replace("node js", "node.js")
    s = s.replace("reactjs", "react")
    s = s.replace("vuejs", "vue")
    s = s.replace("angularjs", "angular")
    s = re.sub(r"[^a-z0-9+#.\-/ ]+", " ", s)
    s = re.sub(r"\s+", " ", s)
    return s.strip()


def _dedup(items: Iterable[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for item in items:
        n = _normalize(item)
        if n and n not in seen:
            seen.add(n)
            result.append(item.strip())
    return result


# ── Skill extraction ──────────────────────────────────────────────────────────

def _is_education_phrase(phrase: str) -> bool:
    """Return True if the phrase is an education/HR requirement, not a technical skill."""
    return any(kw in phrase for kw in _EDUCATION_KEYWORDS)


def _extract_raw_skills(text: str) -> list[str]:
    """Extract pure skill names from a text chunk.
    Strips qualifier words, stopwords, and education phrases."""
    all_stop = _STOPWORDS | set(_QUALIFIERS.keys()) | _QUALIFIER_CONTEXT
    tokens: list[str] = []

    # Remove known multi-word skills first, then process the rest
    remaining = text
    for mws in sorted(_MULTI_WORD_SKILLS, key=len, reverse=True):
        if mws in remaining:
            tokens.append(mws)
            remaining = remaining.replace(mws, " ")

    for w in re.sub(r"\s+", " ", remaining).strip().split():
        if len(w) >= 2 and w not in all_stop:
            tokens.append(w)

    return list(dict.fromkeys(tokens))  # dedup preserving order


def _parse_description_to_groups(text: Optional[str]) -> list[dict]:
    """Parse one requirement description into skill groups.
    Each group: {"skills": [...], "qualifier": str, "logic": "OR"|"AND"}
    Examples:
      "Java"                              → [{"skills":["java"], "qualifier":"any", "logic":"AND"}]
      "Basic knowledge of Java or JS"    → [{"skills":["java","js"], "qualifier":"basic", "logic":"OR"}]
      "Advanced Python and Django"        → [{"skills":["python","django"], "qualifier":"advanced", "logic":"AND"}]
    """
    normalized = _normalize(text)
    if not normalized:
        return []

    groups: list[dict] = []

    for part in _SPLIT_RE.split(normalized):
        # Strip common HR preambles and year ranges
        chunk = re.sub(
            r"\b(at least|minimum|preferred|required|nice to have|"
            r"you (should|must|need|have)|we (need|want|require))\b", " ", part.strip()
        )
        chunk = re.sub(r"\d+\+?\s*(years?|ans?)", " ", chunk)
        chunk = re.sub(r"\s+", " ", chunk).strip()

        if not chunk or _is_education_phrase(chunk):
            continue

        # ── Detect qualifier at the start ─────────────────────────────────────
        words = chunk.split()
        qualifier = "any"
        if words and words[0] in _QUALIFIERS:
            qualifier = _QUALIFIERS[words[0]]
            chunk = " ".join(words[1:]).strip()
            # Strip trailing qualifier context words ("knowledge of", "level", …)
            chunk = re.sub(
                r"^(" + "|".join(re.escape(w) for w in _QUALIFIER_CONTEXT) + r")\s*(of\s+)?",
                "", chunk
            ).strip()

        # ── Detect OR vs AND and split ────────────────────────────────────────
        if _OR_SPLIT_RE.search(chunk):
            logic = "OR"
            parts = _OR_SPLIT_RE.split(chunk)
        elif _AND_SPLIT_RE.search(chunk):
            logic = "AND"
            parts = _AND_SPLIT_RE.split(chunk)
        else:
            logic = "AND"
            parts = [chunk]

        # ── Extract skill tokens from each sub-part ───────────────────────────
        skills: list[str] = []
        for p in parts:
            skills.extend(_extract_raw_skills(p.strip()))
        skills = list(dict.fromkeys(skills))  # dedup

        if skills:
            groups.append({"skills": skills, "qualifier": qualifier, "logic": logic})

    return groups


def _extract_required_skill_groups(
    requirements: list[JobRequirementInput],
    job_title: Optional[str],
    job_description: Optional[str],
) -> list[dict]:
    """Return structured skill groups from job requirements."""
    groups: list[dict] = []

    # Priority: SKILL / TECHNICAL requirements
    for req in requirements:
        cat = (req.category or "").upper()
        if cat in ("SKILL", "TECHNICAL", "TECHNOLOGY", "COMPÉTENCE"):
            # Use structured skill_level if set by recruiter — no parsing needed
            if req.skill_level:
                qualifier = req.skill_level.lower()  # BASIC→basic, INTERMEDIATE→intermediate, ADVANCED→advanced
                parsed = _parse_description_to_groups(req.description)
                for g in parsed:
                    g["qualifier"] = qualifier  # override parsed qualifier with structured one
                groups.extend(parsed)
            else:
                groups.extend(_parse_description_to_groups(req.description))

    # Fallback: all requirements
    if not groups:
        for req in requirements:
            groups.extend(_parse_description_to_groups(req.description))

    # Last fallback: job title + description
    if not groups:
        for g in _parse_description_to_groups(job_title):
            groups.append(g)
        for g in _parse_description_to_groups(job_description):
            groups.append(g)

    # Dedup by skill set
    seen: set[tuple] = set()
    deduped: list[dict] = []
    for g in groups:
        key = tuple(sorted(g["skills"]))
        if key not in seen:
            seen.add(key)
            deduped.append(g)

    return deduped


def _collect_cv_skills(cv: CvAnalysisResult) -> set[str]:
    """Collect all structured skills from CV.
    Multi-word skills are expanded into individual tokens and 2-word sub-phrases
    so a single required word can match against a longer compound skill."""
    collected: set[str] = set()

    def add(values: Optional[Iterable[Optional[str]]]) -> None:
        for v in (values or []):
            n = _normalize(v)
            if not n or len(n) <= 1:
                continue
            collected.add(n)
            words = n.split()
            if len(words) > 1:
                for w in words:
                    if len(w) >= 3 and w not in _STOPWORDS:
                        collected.add(w)
                for i in range(len(words) - 1):
                    collected.add(f"{words[i]} {words[i+1]}")
            # Extract sub-words from compound terms (pl/sql→sql, mysql→sql, postgresql→sql)
            for delimiter in ['/', '-', '.']:
                if delimiter in n:
                    for part in n.split(delimiter):
                        if len(part) >= 2 and part not in _STOPWORDS:
                            collected.add(part)
            # Extract trailing technology keyword from compound words (mysql→sql, mongodb→db)
            for suffix in ['sql', 'db', 'js', 'py', 'ml']:
                if n.endswith(suffix) and len(n) > len(suffix) + 1:
                    collected.add(suffix)

    add(cv.skills)
    add(cv.knowledge)
    add(cv.soft_skills)

    for exp in (cv.work_experience or []):
        add(exp.skills_used)
    for proj in (cv.projects or []):
        add(proj.skills_used)
    for hack in (cv.hackathons or []):
        add(hack.skills_used)

    if cv.github_profile:
        add(cv.github_profile.all_technologies)
        add(cv.github_profile.all_repo_frameworks)
        add(cv.github_profile.cv_skills_confirmed)
        add(cv.github_profile.cv_skills_likely)

    return collected


# ── Embeddings ────────────────────────────────────────────────────────────────

def _embed(text: str) -> list[float]:
    if not text:
        return []
    try:
        resp = ollama_client.embeddings(model=EMBEDDING_MODEL, prompt=text)
        vec = resp.get("embedding") if isinstance(resp, dict) else getattr(resp, "embedding", None)
        if not isinstance(vec, list) or not vec:
            return []
        return [float(x) for x in vec]
    except Exception:
        return []


def _embed_cached(text: str, cache: dict[str, list[float]]) -> list[float]:
    key = text.strip()
    if not key:
        return []
    if key not in cache:
        cache[key] = _embed(key)
    return cache[key]


def _cosine(a: list[float], b: list[float]) -> float:
    if not a or not b:
        return 0.0
    n = min(len(a), len(b))
    dot = na = nb = 0.0
    for i in range(n):
        dot += a[i] * b[i]
        na  += a[i] * a[i]
        nb  += b[i] * b[i]
    return 0.0 if na <= 0 or nb <= 0 else dot / (math.sqrt(na) * math.sqrt(nb))


# ── Skill matching ────────────────────────────────────────────────────────────

def _match_skill_scored(
    req_skill: str,
    cv_skills: set[str],
    embed_cache: dict[str, list[float]],
    matched_threshold: float = SKILL_SEM_THRESHOLD,
) -> tuple[int, str]:
    """
    Match a required skill against all CV skills.
    Returns (score 0-100, status: 'matched'|'partial'|'missing').
    matched_threshold is driven by the qualifier level (basic/intermediate/advanced).
    """
    normalized = _normalize(req_skill)
    if not normalized:
        return 0, "missing"

    # 1 — Exact match
    if normalized in cv_skills:
        return 100, "matched"

    # 2 — Token overlap
    req_tokens = {w for w in normalized.split() if len(w) >= 3 and w not in _STOPWORDS}
    if req_tokens:
        best_overlap = 0.0
        for cv_skill in cv_skills:
            cv_tokens = set(cv_skill.split())
            overlap = req_tokens & cv_tokens
            if overlap:
                ratio = len(overlap) / len(req_tokens)
                if ratio > best_overlap:
                    best_overlap = ratio
        if best_overlap >= 1.0:
            return 95, "matched"
        elif best_overlap >= 0.5:
            return 70, "partial"
        elif best_overlap > 0:
            return 45, "partial"

    # 3 — Semantic similarity
    req_vec = _embed_cached(normalized, embed_cache)
    if not req_vec:
        return 0, "missing"

    best_sim = 0.0
    for cv_skill in cv_skills:
        sim = _cosine(req_vec, _embed_cached(cv_skill, embed_cache))
        if sim > best_sim:
            best_sim = sim

    score = round(max(0.0, min(best_sim, 1.0)) * 100)

    if score >= matched_threshold * 100:
        return score, "matched"
    elif score >= SKILL_SEM_PARTIAL_THRESHOLD * 100:
        return score, "partial"
    else:
        return score, "missing"


# ── Context builders for embedding ───────────────────────────────────────────

def _build_job_text(request: SemanticMatchRequest) -> str:
    parts: list[str] = []
    if request.job_title:
        parts.append(f"Job title: {request.job_title}")
    if request.job_description:
        parts.append(f"Description: {request.job_description}")
    if request.requirements:
        lines = []
        for r in request.requirements:
            desc = (r.description or "").strip()
            if not desc:
                continue
            cat = (r.category or "REQUIREMENT").upper()
            lines.append(f"[{cat}] {desc}")
        if lines:
            parts.append("Requirements:\n" + "\n".join(lines))
    return "\n\n".join(parts).strip()


def _build_cv_text(cv: CvAnalysisResult) -> str:
    parts: list[str] = []
    if cv.summary:
        parts.append(f"Summary: {cv.summary}")
    if cv.seniority_level:
        parts.append(f"Seniority: {cv.seniority_level}")
    if cv.total_years_experience is not None:
        parts.append(f"Years of experience: {cv.total_years_experience}")
    if cv.skills:
        parts.append("Technical skills: " + ", ".join([s for s in cv.skills if s][:40]))
    if cv.soft_skills:
        parts.append("Soft skills: " + ", ".join([s for s in cv.soft_skills if s][:15]))
    if cv.work_experience:
        lines = []
        for e in cv.work_experience[:6]:
            skills = ", ".join(e.skills_used[:15]) if e.skills_used else ""
            desc   = (e.description or "").strip()
            lines.append(f"- {e.title or ''} @ {e.company or ''} | {skills}\n  {desc}".strip())
        parts.append("Experience:\n" + "\n".join(lines))
    if cv.projects:
        lines = []
        for p in cv.projects[:4]:
            skills = ", ".join(p.skills_used[:15]) if p.skills_used else ""
            lines.append(f"- {p.title} | {skills}".strip())
        parts.append("Projects:\n" + "\n".join(lines))
    if cv.github_profile:
        tech = [*(cv.github_profile.all_technologies or []), *(cv.github_profile.all_repo_frameworks or [])]
        if tech:
            parts.append("GitHub technologies: " + ", ".join(tech[:50]))
    return "\n\n".join(parts).strip()


def _build_requirement_text(req: JobRequirementInput) -> str:
    cat = (req.category or "REQUIREMENT").upper()
    years = []
    if req.min_years is not None:
        years.append(f"min {req.min_years} years")
    if req.max_years is not None:
        years.append(f"max {req.max_years} years")
    suffix = f" ({', '.join(years)})" if years else ""
    return f"[{cat}] {(req.description or '').strip()}{suffix}".strip()


_EVIDENCE_SKIP = _STOPWORDS | {
    "skill", "skills", "experience", "education", "certification",
    "language", "requirement", "technical", "category",
}


def _match_education_req(
    description: str,
    cv: CvAnalysisResult,
    degree_level: Optional[str] = None,
    enrollment_type: Optional[str] = None,
) -> tuple[int, str]:
    """Match an EDUCATION requirement against the candidate's education entries.
    Structured fields (degree_level, enrollment_type) take priority over text parsing."""
    import datetime
    _ONGOING_MARKERS = {"ongoing", "pursuing", "current", "present", "in progress"}
    current_year = str(datetime.datetime.now().year)
    next_year    = str(datetime.datetime.now().year + 1)

    if not cv.education:
        return 0, "missing"

    has_ongoing = any(
        any(w in _normalize(edu.degree or "") for w in _ONGOING_MARKERS)
        or any(w in _normalize(edu.year or "") for w in _ONGOING_MARKERS | {current_year, next_year})
        for edu in cv.education
    )
    has_completed = any(
        not any(w in _normalize(edu.degree or "") for w in _ONGOING_MARKERS)
        and not any(w in _normalize(edu.year or "") for w in _ONGOING_MARKERS | {current_year, next_year})
        for edu in cv.education
    )

    # ── Structured enrollment_type — set by recruiter via dropdown ────────────
    if enrollment_type:
        et = enrollment_type.upper()
        if et == "BOTH":
            req_wants_student, req_wants_graduate = True, True
        elif et == "STUDENT":
            req_wants_student, req_wants_graduate = True, False
        elif et == "GRADUATE":
            req_wants_student, req_wants_graduate = False, True
        else:
            req_wants_student, req_wants_graduate = False, False
    else:
        # Fall back to text parsing
        req_n = _normalize(description)
        req_wants_student  = any(kw in req_n for kw in {"student", "pursuing", "enrolled", "studying"})
        req_wants_graduate = any(kw in req_n for kw in {"graduate", "graduated", "completed", "degree"})

    # ── Structured degree_level — set by recruiter via dropdown ──────────────
    _STRUCTURED_DEGREE_MAP = {
        "BAC": 0, "LICENCE_BACHELOR": 2, "MASTER": 3, "PHD": 4, "ANY": None,
    }
    structured_req_level: Optional[int] = None
    if degree_level and degree_level.upper() in _STRUCTURED_DEGREE_MAP:
        structured_req_level = _STRUCTURED_DEGREE_MAP[degree_level.upper()]

    # ── Enrollment check ─────────────────────────────────────────────────────
    if req_wants_student or req_wants_graduate:
        if req_wants_student and req_wants_graduate:
            return (100, "matched") if (has_ongoing or has_completed) else (0, "missing")
        if req_wants_student:
            if has_ongoing:   return 100, "matched"
            if has_completed: return 60, "partial"
            return 0, "missing"
        if req_wants_graduate:
            if has_completed: return 100, "matched"
            if has_ongoing:   return 70, "partial"
            return 0, "missing"

    # ── Degree level check ────────────────────────────────────────────────────
    # Use structured degree_level if set, otherwise parse from description text
    if structured_req_level is not None:
        req_level: Optional[int] = structured_req_level
    elif degree_level and degree_level.upper() == "ANY":
        req_level = None  # any education is acceptable
    else:
        req_level = None
        req_n = _normalize(description)
        for kw, level in _DEGREE_LEVELS.items():
            if kw in req_n:
                req_level = level
                break

    # Find highest degree level the candidate has
    cv_max = 0
    for edu in cv.education:
        deg_n = _normalize(edu.degree or "")
        for kw, level in _DEGREE_LEVELS.items():
            if kw in deg_n and level > cv_max:
                cv_max = level

    if req_level is None:
        # Requirement is ambiguous — can't verify, return neutral score
        return (50, "partial") if cv.education else (0, "missing")

    if cv_max >= req_level:
        return 100, "matched"
    elif cv_max == req_level - 1:
        return 55, "partial"   # one level below
    else:
        return 10, "missing"


def _match_certification_req(description: str, cv: CvAnalysisResult) -> tuple[int, str]:
    """Match a CERTIFICATION requirement against the candidate's certifications."""
    req_tokens = {t for t in _normalize(description).split()
                  if len(t) > 2 and t not in _EVIDENCE_SKIP}
    if not req_tokens:
        return 0, "missing"

    for cert in (cv.certifications or []):
        cert_n = _normalize(cert)
        if any(t in cert_n for t in req_tokens):
            return 100, "matched"

    return 0, "missing"


def calibrate_thresholds() -> dict:
    """
    Run known tech pairs through the embedding model and compute
    recommended SKILL_SEM_THRESHOLD and SKILL_SEM_PARTIAL_THRESHOLD values.
    Call GET /api/cv-parser/calibrate-thresholds to use this.
    """
    cache: dict[str, list[float]] = {}
    results = []
    for a, b, expected in _CALIBRATION_PAIRS:
        sim = _cosine(_embed_cached(_normalize(a), cache), _embed_cached(_normalize(b), cache))
        results.append({"a": a, "b": b, "expected": expected,
                         "similarity": round(sim, 4), "score": round(sim * 100)})

    matched_sims   = [r["similarity"] for r in results if r["expected"] == "matched"]
    partial_sims   = [r["similarity"] for r in results if r["expected"] == "partial"]
    unrelated_sims = [r["similarity"] for r in results if r["expected"] == "missing"]

    # Threshold = minimum similarity of the target group (conservative)
    rec_matched = round(min(matched_sims), 2)  if matched_sims   else SKILL_SEM_THRESHOLD
    rec_partial = round(min(partial_sims), 2)  if partial_sims   else SKILL_SEM_PARTIAL_THRESHOLD

    return {
        "pairs": results,
        "group_scores": {
            "matched":   sorted(round(s * 100) for s in matched_sims),
            "partial":   sorted(round(s * 100) for s in partial_sims),
            "unrelated": sorted(round(s * 100) for s in unrelated_sims),
        },
        "recommended": {
            "matched_threshold": round(rec_matched * 100),
            "partial_threshold": round(rec_partial * 100),
        },
        "current": {
            "matched_threshold": round(SKILL_SEM_THRESHOLD * 100),
            "partial_threshold": round(SKILL_SEM_PARTIAL_THRESHOLD * 100),
        },
        "advice": (
            "Set SKILL_SEM_THRESHOLD to the minimum score of the 'matched' group, "
            "and SKILL_SEM_PARTIAL_THRESHOLD to the minimum score of the 'partial' group."
        ),
    }


def _extract_source_tags(req_text: str, cv: CvAnalysisResult) -> str:
    """
    Return pipe-separated source tags showing WHERE in the CV a requirement is matched.
    Format: "CV Skills | Experience: Vermeg (2026) | GitHub Verified"
    """
    clean = re.sub(r"^\[[^\]]+\]\s*", "", req_text.strip())
    tokens = {t for t in _normalize(clean).split()
              if len(t) > 2 and t not in _EVIDENCE_SKIP}
    if not tokens:
        return ""

    def matches(text: Optional[str]) -> bool:
        if not text:
            return False
        n = _normalize(text)
        return any(t in n for t in tokens)

    tags: list[str] = []

    # 1 — CV Skills section
    matched_skills = [s for s in (cv.skills or []) if matches(s)]
    if matched_skills:
        tags.append("CV Skills")

    # 2 — Work experience
    for exp in (cv.work_experience or []):
        exp_match = any(matches(s) for s in (exp.skills_used or [])) or matches(exp.description)
        if exp_match:
            company = exp.company or "Experience"
            year = ""
            if exp.duration:
                y = re.search(r"\d{4}", exp.duration)
                year = f" ({y.group()})" if y else ""
            tags.append(f"Experience: {company}{year}")
            break  # one experience tag is enough

    # 3 — Projects
    for proj in (cv.projects or []):
        if any(matches(s) for s in (proj.skills_used or [])) or matches(proj.description):
            tags.append(f"Project: {proj.title[:30]}" if proj.title else "Project")
            break

    # 4 — GitHub verification
    if cv.github_profile:
        gh_confirmed = any(matches(s) for s in (cv.github_profile.cv_skills_confirmed or []))
        gh_likely    = any(matches(s) for s in (cv.github_profile.cv_skills_likely or []))
        gh_tech      = any(matches(s) for s in (cv.github_profile.all_technologies or []))
        if gh_confirmed:
            tags.append("GitHub Verified")
        elif gh_likely or gh_tech:
            tags.append("GitHub Detected")

    return " | ".join(tags) if tags else ""

# ── Experience / seniority helpers ────────────────────────────────────────────

def _required_years(
    requirements: list[JobRequirementInput],
    job_title: Optional[str],
    job_description: Optional[str],
) -> float:
    years = [
        float(r.min_years)
        for r in requirements
        if r.min_years is not None and (r.category or "").upper() == "EXPERIENCE"
    ]
    if years:
        return max(years)
    source = f"{job_title or ''} {job_description or ''}".lower()
    m = re.search(r"(\d+(?:\.\d+)?)\+?\s*(?:years?|ans?)", source)
    return float(m.group(1)) if m else 0.0


def _required_seniority(required_years: float, job_title: Optional[str], job_description: Optional[str]) -> Optional[str]:
    source = _normalize(f"{job_title or ''} {job_description or ''}")
    if any(w in source for w in ["senior", "lead", "principal", "architect"]):
        return "SENIOR"
    if any(w in source for w in ["mid", "intermediate"]):
        return "MID"
    if any(w in source for w in ["junior", "entry"]):
        return "JUNIOR"
    if any(w in source for w in ["intern", "internship", "stage", "stagiaire"]):
        return "INTERN"
    if required_years >= 5:   return "SENIOR"
    if required_years >= 2:   return "MID"
    if required_years > 0:    return "JUNIOR"
    return None


# ── Score explanation ─────────────────────────────────────────────────────────

def _build_score_explanation(
    job_fit_score: int,
    matched: list[str],
    missing: list[str],
    req_semantic_score: float,
    experience_gap: float,
    required_years: float,
    candidate_years: float,
    seniority_match: bool,
    candidate_seniority: str,
    required_seniority: Optional[str],
) -> str:
    lines: list[str] = []

    # Skills
    total_skills = len(matched) + len(missing)
    if total_skills > 0:
        pct = round(len(matched) / total_skills * 100)
        matched_str = ", ".join(matched[:5]) + ("…" if len(matched) > 5 else "")
        missing_str = ", ".join(missing[:5]) + ("…" if len(missing) > 5 else "")
        skill_line = f"Skills: {len(matched)}/{total_skills} required skills matched ({pct}%)"
        if matched:
            skill_line += f" — found: {matched_str}"
        if missing:
            skill_line += f" — missing: {missing_str}"
        lines.append(skill_line)
    else:
        lines.append("Skills: No specific skill requirements defined for this job.")

    # Semantic alignment
    alignment_pct = round(req_semantic_score * 100)
    if alignment_pct >= 75:
        label = "strong"
    elif alignment_pct >= 55:
        label = "moderate"
    else:
        label = "weak"
    lines.append(f"Semantic alignment: {alignment_pct}% — {label} match between candidate profile and job requirements.")

    # Experience
    if required_years > 0:
        if experience_gap > 0:
            lines.append(
                f"Experience: candidate has {candidate_years}yr, role requires {required_years}yr "
                f"(gap of {experience_gap}yr)."
            )
        else:
            lines.append(
                f"Experience: candidate has {candidate_years}yr — meets the {required_years}yr requirement."
            )
    else:
        lines.append(f"Experience: {candidate_years}yr — no minimum specified for this role.")

    # Seniority
    if required_seniority:
        status = "matches" if seniority_match else "does not meet"
        lines.append(
            f"Seniority: candidate is {candidate_seniority or 'UNKNOWN'}, "
            f"role requires {required_seniority} — {status} the requirement."
        )

    return " | ".join(lines)


# ── LLM analysis ──────────────────────────────────────────────────────────────

def _llm_analysis(
    request: SemanticMatchRequest,
    score: int,
    requirement_scores: list[dict],
    matched: list[str],
    missing: list[str],
    experience_gap: float,
    seniority_match: bool,
) -> tuple[list[str], list[str], str, list[str]]:
    cv = request.cv_analysis
    payload = {
        "job_title": request.job_title,
        "requirements": [
            {"category": r.category, "description": r.description,
             "min_years": r.min_years, "max_years": r.max_years}
            for r in request.requirements
        ],
        "candidate": {
            "summary": cv.summary,
            "skills": (cv.skills or [])[:30],
            "soft_skills": (cv.soft_skills or [])[:15],
            "seniority_level": cv.seniority_level,
            "total_years_experience": cv.total_years_experience,
            "work_experience": [
                {"title": e.title, "company": e.company,
                 "skills_used": (e.skills_used or [])[:12], "description": e.description}
                for e in (cv.work_experience or [])[:6]
            ],
            "projects": [
                {"title": p.title, "skills_used": (p.skills_used or [])[:12], "description": p.description}
                for p in (cv.projects or [])[:4]
            ],
        },
        "signals": {
            "job_fit_score": score,
            "matched_skills": matched,
            "missing_skills": missing,
            "experience_gap": experience_gap,
            "seniority_match": seniority_match,
        },
    }
    prompt = (
        "You are a recruiter assistant. Analyze the candidate fit using only the provided data.\n"
        "Return STRICT JSON with keys:\n"
        "  strengths: array of strings (max 5) — what the candidate does well for this role\n"
        "  weaknesses: array of strings (max 5) — gaps or concerns\n"
        "  recommendation: exactly one of HIRE, INTERVIEW, REVIEW, REJECT\n"
        "  interview_questions: array of strings (max 5) — questions targeting gaps\n"
        f"Data:\n{json.dumps(payload, ensure_ascii=True)}"
    )
    try:
        resp = ollama_client.chat(
            model=ANALYSIS_MODEL,
            messages=[{"role": "user", "content": prompt}],
            options={"temperature": 0.2},
            format="json",
        )
        content = (((resp or {}).get("message") or {}).get("content") or "").strip()
        parsed = json.loads(content) if content else {}
        strengths   = [str(x) for x in parsed.get("strengths", [])          if isinstance(x, str)][:5]
        weaknesses  = [str(x) for x in parsed.get("weaknesses", [])         if isinstance(x, str)][:5]
        questions   = [str(x) for x in parsed.get("interview_questions", []) if isinstance(x, str)][:5]
        rec = str(parsed.get("recommendation", "REVIEW")).upper()
        if rec not in {"HIRE", "INTERVIEW", "REVIEW", "REJECT"}:
            rec = "REVIEW"
        return strengths, weaknesses, rec, questions
    except Exception:
        rec = "INTERVIEW" if score >= 70 else ("REVIEW" if score >= 45 else "REJECT")
        strengths  = [f"Matched {len(matched)} required skill(s)." if matched else "Partial profile alignment."]
        weaknesses = [f"Missing {len(missing)} required skill(s)." if missing else "No major gaps detected."]
        questions  = [f"Describe your experience with {missing[0]}?"] if missing else ["Which project best demonstrates your fit?"]
        return strengths, weaknesses, rec, questions


# ── Main entry point ──────────────────────────────────────────────────────────

def match_job_to_cv(request: SemanticMatchRequest) -> SemanticMatchResult:
    cv            = request.cv_analysis
    embed_cache: dict[str, list[float]] = {}

    # ── CV text + vector — computed once, reused by skills blend and global sim ─
    cv_text = _build_cv_text(cv)
    cv_vec  = _embed_cached(cv_text, embed_cache)

    # ── Required skills ───────────────────────────────────────────────────────
    required_skill_groups = _extract_required_skill_groups(
        request.requirements, request.job_title, request.job_description
    )
    cv_skills = _collect_cv_skills(cv)

    matched: list[str] = []
    missing: list[str] = []
    skill_scores: list[dict] = []

    def _blend(keyword_score: int, skill_text: str) -> tuple[int, str]:
        """Blend keyword match score with embedding depth score.
        keyword_score: precise — did the candidate declare this skill?
        embedding: contextual — how deeply is it present throughout the full CV?
        60/40 split: keyword is more reliable, embedding adds evidence depth.
        """
        emb_sim   = _cosine(_embed_cached(skill_text, embed_cache), cv_vec)
        emb_score = max(0.0, min((emb_sim + 1.0) / 2.0, 1.0)) * 100
        final     = round(0.6 * keyword_score + 0.4 * emb_score)
        if final >= SKILL_SEM_THRESHOLD * 100:
            return final, "matched"
        elif final >= SKILL_SEM_PARTIAL_THRESHOLD * 100:
            return final, "partial"
        return final, "missing"

    for group in required_skill_groups:
        threshold = _QUALIFIER_THRESHOLDS.get(group["qualifier"], SKILL_SEM_THRESHOLD)
        logic     = group["logic"]
        qualifier = group["qualifier"]
        prefix    = f"[{qualifier.upper()}] " if qualifier != "any" else ""

        results = [
            (skill, *_match_skill_scored(skill, cv_skills, embed_cache, threshold))
            for skill in group["skills"]
        ]

        if logic == "OR":
            # Keyword: best individual skill score
            _, best_kw_score, _ = max(results, key=lambda x: x[1])
            label = " or ".join(group["skills"])
            # Blend using the full label so embedding sees all alternatives
            final_score, final_status = _blend(best_kw_score, label)
            skill_scores.append({"skill": f"{prefix}{label}", "score": final_score, "status": final_status})
            (matched if final_status == "matched" else missing).append(label)
        else:
            # AND — each skill blended independently
            for skill, kw_score, _ in results:
                final_score, final_status = _blend(kw_score, skill)
                skill_scores.append({"skill": f"{prefix}{skill}", "score": final_score, "status": final_status})
                (matched if final_status == "matched" else missing).append(skill)

    # Skills component: average score across all required skills (not binary ratio)
    if skill_scores:
        skills_ratio = sum(s["score"] for s in skill_scores) / (100 * len(skill_scores))
    else:
        skills_ratio = 1.0

    # ── Experience gap ────────────────────────────────────────────────────────
    required_years  = _required_years(request.requirements, request.job_title, request.job_description)
    candidate_years = float(cv.total_years_experience or 0.0)
    experience_gap  = round(max(required_years - candidate_years, 0.0), 1)
    experience_score = 1.0 if required_years <= 0 else max(0.0, min(candidate_years / required_years, 1.0))

    # ── Seniority ─────────────────────────────────────────────────────────────
    required_seniority  = _required_seniority(required_years, request.job_title, request.job_description)
    candidate_seniority = (cv.seniority_level or "").upper()
    seniority_match     = True
    if required_seniority and candidate_seniority in _SENIORITY_ORDER:
        seniority_match = _SENIORITY_ORDER[candidate_seniority] >= _SENIORITY_ORDER[required_seniority]
    seniority_score = 1.0 if seniority_match else 0.5

    # ── Global embedding similarity ───────────────────────────────────────────
    job_text = _build_job_text(request)
    global_sim       = _cosine(_embed_cached(job_text, embed_cache), cv_vec)
    global_sim_score = max(0.0, min((global_sim + 1.0) / 2.0, 1.0))

    # ── Per-requirement semantic scoring ──────────────────────────────────────
    requirement_scores: list[dict] = []
    req_semantic_score = global_sim_score  # fallback when no requirements
    weighted_sum  = 0.0
    total_weight  = 0.0

    for req in (request.requirements or []):
        req_text = _build_requirement_text(req)
        if not req_text:
            continue

        cat = (req.category or "").upper()
        weight = float(req.weight if req.weight is not None else 1.0)
        evidence = _extract_source_tags(req_text, cv)

        # EDUCATION and CERTIFICATION use structured matchers — not embedding
        if cat == "EDUCATION":
            score_val, _ = _match_education_req(
                req.description or "", cv,
                degree_level=req.degree_level,
                enrollment_type=req.enrollment_type,
            )
            req_score = score_val / 100.0
        elif cat == "CERTIFICATION":
            score_val, _ = _match_certification_req(req.description or "", cv)
            req_score = score_val / 100.0
        elif cat in ("SKILL", "TECHNICAL", "TECHNOLOGY", "COMPÉTENCE"):
            # Use the same blended formula as the skills breakdown —
            # so the requirement card and the skills bar always show the same number.
            groups = _parse_description_to_groups(req.description or "")
            blended_scores: list[int] = []
            for g in groups:
                thresh   = _QUALIFIER_THRESHOLDS.get(g["qualifier"], SKILL_SEM_THRESHOLD)
                kw_vals  = [_match_skill_scored(s, cv_skills, embed_cache, thresh)[0] for s in g["skills"]]
                if g["logic"] == "OR":
                    best_kw   = max(kw_vals) if kw_vals else 0
                    label     = " or ".join(g["skills"])
                    emb_sim   = _cosine(_embed_cached(label, embed_cache), cv_vec)
                    emb_score = max(0.0, min((emb_sim + 1.0) / 2.0, 1.0)) * 100
                    blended_scores.append(round(0.6 * best_kw + 0.4 * emb_score))
                else:
                    for skill, kw in zip(g["skills"], kw_vals):
                        emb_sim   = _cosine(_embed_cached(skill, embed_cache), cv_vec)
                        emb_score = max(0.0, min((emb_sim + 1.0) / 2.0, 1.0)) * 100
                        blended_scores.append(round(0.6 * kw + 0.4 * emb_score))
            req_score = (sum(blended_scores) / len(blended_scores)) / 100 if blended_scores else 0.0
        else:
            # EXPERIENCE / other — embedding similarity
            req_sim   = _cosine(_embed_cached(req_text, embed_cache), cv_vec)
            req_score = max(0.0, min((req_sim + 1.0) / 2.0, 1.0))

        requirement_scores.append({
            "category":    cat or "REQUIREMENT",
            "description": req.description or "",
            "score":       round(req_score * 100),
            "weight":      weight,
            "evidence":    evidence,
        })
        weighted_sum  += req_score * weight
        total_weight  += weight

    if total_weight > 0:
        req_semantic_score = weighted_sum / total_weight

    embedding_score = round(
        (global_sim_score * 0.4 + req_semantic_score * 0.6) * 100
    )

    # ── Final score blend — use recruiter-defined weights if provided ─────────
    w = request.scoring_weights
    if w is not None:
        # Normalize so weights always sum to 1.0 (guard against rounding errors)
        total = (w.skills or 0) + (w.semantic or 0) + (w.experience or 0) + (w.seniority or 0)
        if total <= 0:
            total = 1.0
        w_skills     = w.skills     / total
        w_semantic   = w.semantic   / total
        w_experience = w.experience / total
        w_seniority  = w.seniority  / total
    else:
        # Default balanced weights
        w_skills, w_semantic, w_experience, w_seniority = 0.40, 0.35, 0.15, 0.10

    # Semantic signal = blend of requirement-level + global embedding
    semantic_signal = req_semantic_score * 0.7 + global_sim_score * 0.3

    blended = (
        skills_ratio    * w_skills     +
        semantic_signal * w_semantic   +
        experience_score * w_experience +
        seniority_score  * w_seniority
    )
    job_fit_score = max(0, min(round(blended * 100), 100))

    # ── Score explanation (deterministic — no LLM) ───────────────────────────
    score_explanation = _build_score_explanation(
        job_fit_score      = job_fit_score,
        matched            = matched,
        missing            = missing,
        req_semantic_score = req_semantic_score,
        experience_gap     = experience_gap,
        required_years     = required_years,
        candidate_years    = candidate_years,
        seniority_match    = seniority_match,
        candidate_seniority= candidate_seniority,
        required_seniority = required_seniority,
    )

    # ── LLM narrative ─────────────────────────────────────────────────────────
    strengths, weaknesses, recommendation, interview_questions = _llm_analysis(
        request=request,
        score=job_fit_score,
        requirement_scores=requirement_scores,
        matched=matched,
        missing=missing,
        experience_gap=experience_gap,
        seniority_match=seniority_match,
    )

    return SemanticMatchResult(
        application_id          = request.application_id,
        job_fit_score           = job_fit_score,
        required_skills_matched = matched,
        required_skills_missing = missing,
        skill_scores            = skill_scores,
        experience_gap          = experience_gap,
        seniority_match         = seniority_match,
        embedding_score         = max(0, min(embedding_score, 100)),
        requirement_scores      = requirement_scores,
        strengths               = strengths,
        weaknesses              = weaknesses,
        recommendation          = recommendation,
        interview_questions     = interview_questions,
        score_explanation       = score_explanation,
    )
