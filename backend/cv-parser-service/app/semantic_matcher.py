import datetime
import math
import os
import re
import json
import logging
from typing import Callable, Iterable, Optional

import ollama
import requests

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

# Job-microservice base URL. The matcher uses this to read and write the cached
# job embedding via GET/PUT /api/jobs/{id}/embedding so the same job vector is
# computed once across all applicants instead of recomputed on every match.
JOB_SERVICE_URL = os.getenv("JOB_SERVICE_URL", "http://job-microservice:8080")

# Cosine similarity threshold for semantic skill synonyms (e.g. React / ReactJS)
# Embedding similarity thresholds for skill matching.
# The model already encodes language-framework relationships (Java/Spring Boot, PHP/Laravel, etc.)
# — lower thresholds let that natural proximity register as partial credit.
# Calibrated against real nomic-embed-text pairs (see /api/cv-parser/calibrate-thresholds)
# matched group min: 81% (spring/spring boot) — unrelated max: 48% (python/excel)
SKILL_SEM_THRESHOLD         = 0.80  # ≥80% → matched (green):  near-synonyms and variants
SKILL_SEM_PARTIAL_THRESHOLD = 0.43  # ≥43% → partial (orange): language/framework family relationships

ollama_client = ollama.Client(host=OLLAMA_HOST)

# ── Tech volatility + recency/duration scoring ────────────────────────────────
# Half-life in years: how fast each tech "ages." Used by _recency_factor() to
# discount old evidence in fast-moving tech (Angular, React) more than in slow
# tech (SQL, HTML).
#   - Fast (2yr): breaking releases every 1-2yr; 4yr-old code often unmaintainable
#   - Medium (4yr, default): LTS-cycle languages and platforms
#   - Slow (8yr): mature APIs with no breaking changes for a decade+
_CURRENT_YEAR = datetime.datetime.now().year

_TECH_VOLATILITY: dict[str, float] = {
    # Fast-moving — JS/TS frontend frameworks
    "angular": 2.0, "react": 2.0, "vue": 2.0, "svelte": 2.0,
    "next.js": 2.0, "nextjs": 2.0, "nuxt": 2.0, "nuxt.js": 2.0,
    "remix": 2.0, "ember": 2.0, "sveltekit": 2.0, "astro": 2.0,
    # Fast-moving — mobile cross-platform
    "flutter": 2.0, "react native": 2.0, "swiftui": 2.0,
    # Fast-moving — ML/AI libs
    "tensorflow": 2.0, "pytorch": 2.0, "keras": 2.0,
    "transformers": 2.0, "langchain": 2.0, "huggingface": 2.0,
    # Slow-moving — mature, stable APIs
    "sql": 8.0, "html": 8.0, "css": 8.0, "bash": 8.0, "shell": 8.0,
    "git": 8.0, "linux": 8.0, "unix": 8.0, "c": 8.0, "c++": 8.0,
    "regex": 8.0, "json": 8.0, "xml": 8.0, "rest": 8.0, "http": 8.0,
    "mysql": 8.0, "postgresql": 8.0, "postgres": 8.0, "oracle": 8.0,
    "sql server": 8.0, "sqlite": 8.0,
}
_DEFAULT_HALF_LIFE = 4.0

# Framework → required-technology relationships are NOT hardcoded here.
# They come entirely from the LLM-derived per-skill classification in
# skill_intel.py ({skill: {volatility, implies}}), which is computed once per
# skill and cached to a persistent JSON store. This is fully generic — it works
# for any framework the candidate lists, including ones unknown to this codebase.
# See _implied_techs() below.


# Recency: lose at most 30% over 2×half_life years, then floor at 70%.
# Floor exists because knowing what a tech IS still has value even if rusty.
_RECENCY_FLOOR    = 0.70
_RECENCY_MAX_LOSS = 0.30

# Duration boost: log curve so each extra year matters less than the previous one.
# Caps at +30% so a 20yr senior doesn't dominate; pairs symmetrically with recency loss.
_DURATION_BOOST_CAP    = 0.30
_DURATION_BOOST_FACTOR = 0.10   # boost = factor × ln(years)


def _tech_half_life(normalized_skill: str, skill_intel: Optional[dict] = None) -> float:
    """Return half-life (years) for a normalized skill name.

    Lookup order (generic first, hardcoded fallback):
      1. LLM-derived skill_intel[skill]["volatility"] (1-10) → exponential map
         half_life = max(1.5, 2 ** ((10 - volatility) / 3))
         calibrated so rating 1→8yr, 4→4yr, 7→2yr, 10→1yr (floored).
      2. Hardcoded _TECH_VOLATILITY (per-skill or per-word).
      3. _DEFAULT_HALF_LIFE (4yr, medium).
    """
    if skill_intel:
        entry = skill_intel.get(normalized_skill)
        if isinstance(entry, dict):
            vol = entry.get("volatility")
            if isinstance(vol, int) and 1 <= vol <= 10:
                return max(1.5, 2.0 ** ((10 - vol) / 3.0))

    if normalized_skill in _TECH_VOLATILITY:
        return _TECH_VOLATILITY[normalized_skill]
    for word in normalized_skill.split():
        if word in _TECH_VOLATILITY:
            return _TECH_VOLATILITY[word]
    return _DEFAULT_HALF_LIFE


def _recency_factor(normalized_skill: str, year: Optional[int],
                    skill_intel: Optional[dict] = None) -> float:
    """Multiplier ∈ [0.70, 1.0]. 1.0 = current year, decays linearly to 0.70 over 2×half_life."""
    if year is None or year <= 0:
        return 1.0
    years_since = max(0, _CURRENT_YEAR - year)
    half_life   = _tech_half_life(normalized_skill, skill_intel)
    return max(
        _RECENCY_FLOOR,
        1.0 - (years_since / (2.0 * half_life)) * _RECENCY_MAX_LOSS,
    )


def _duration_boost(years_worked: float) -> float:
    """Multiplier ∈ [1.0, 1.30] for cumulative years of practice. Log-curve."""
    if years_worked <= 1.0:
        return 1.0
    boost = 1.0 + math.log(years_worked) * _DURATION_BOOST_FACTOR
    return min(boost, 1.0 + _DURATION_BOOST_CAP)


# ── Duration string parsing ───────────────────────────────────────────────────
# Handles: "2022-2024", "Jan 2020 - Mar 2023", "Since 2023", "2 years", "6 months",
# "2 ans", "Depuis 2023", etc.
_YEAR_RANGE_RE = re.compile(
    r'(\d{4})\s*(?:[-–—/]+|to|au)\s*(\d{4}|present|current|now|ongoing|'
    r'pr[ée]sent|aujourd|en\s+cours)',
    re.IGNORECASE,
)
_SINCE_RE           = re.compile(r'(?:since|depuis|from|de(?:puis)?)\s+(\d{4})', re.IGNORECASE)
_DURATION_YEARS_RE  = re.compile(r'(\d+(?:\.\d+)?)\s*(?:years?|ans?|yrs?)', re.IGNORECASE)
_DURATION_MONTHS_RE = re.compile(r'(\d+(?:\.\d+)?)\s*(?:months?|mois|mos?)', re.IGNORECASE)
_BARE_YEAR_RE       = re.compile(r'\b(\d{4})\b')


def _parse_duration_info(text: Optional[str]) -> tuple[Optional[int], float]:
    """Parse a free-form duration/date string into (latest_year, years_worked).
    Returns (None, 0.0) when nothing parseable is found."""
    if not text:
        return None, 0.0
    t = text.strip().lower()

    m = _YEAR_RANGE_RE.search(t)
    if m:
        start = int(m.group(1))
        end_text = m.group(2)
        end = _CURRENT_YEAR if not end_text.isdigit() else int(end_text)
        if 1990 <= start <= _CURRENT_YEAR + 1 and start <= end:
            return end, float(end - start) or 1.0

    m = _SINCE_RE.search(t)
    if m:
        start = int(m.group(1))
        if 1990 <= start <= _CURRENT_YEAR + 1:
            return _CURRENT_YEAR, float(max(1, _CURRENT_YEAR - start))

    m = _DURATION_YEARS_RE.search(t)
    if m:
        return _CURRENT_YEAR, float(m.group(1))

    m = _DURATION_MONTHS_RE.search(t)
    if m:
        return _CURRENT_YEAR, float(m.group(1)) / 12.0

    m = _BARE_YEAR_RE.search(t)
    if m:
        y = int(m.group(1))
        if 1990 <= y <= _CURRENT_YEAR + 1:
            return y, 1.0

    return None, 0.0


# ── Education degree hierarchy ─────────────────────────────────────────────────
_DEGREE_LEVELS: dict[str, int] = {
    "phd": 4, "doctorate": 4, "doctor": 4,
    # Engineering diploma (ingénieur, bac+5) ranks with Master — NOT with bachelor.
    "ingénieur": 3, "ingenieur": 3, "engineer": 3,
    "master": 3, "msc": 3, "mba": 3,
    "bachelor": 2, "licence": 2, "license": 2, "bsc": 2,
    "engineering": 2, "licence professionnelle": 2,   # "engineering" alone is ambiguous → bachelor-level
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
    "advanced": "advanced",
    "expert": "expert", "guru": "expert", "mastery": "expert",
}

# Words that follow a qualifier and must also be stripped ("basic knowledge of …")
_QUALIFIER_CONTEXT = {"knowledge", "level", "understanding", "familiarity", "proficiency", "of"}

# Minimum effective-score (post-curve) to count as "matched" per qualifier level.
# Aligned 1:1 with _QUALIFIER_BARS below so the matched / partial / missing
# status the UI shows can never contradict the meets_qualifier signal.
_QUALIFIER_THRESHOLDS: dict[str, float] = {
    "basic":        0.45,                          # bar / 100
    "intermediate": 0.65,
    "advanced":     0.80,
    "expert":       0.90,
    "any":          SKILL_SEM_THRESHOLD,           # 0.80 — default (no qualifier)
}

# Qualifier-aware scoring tables — calibrated so each level expresses a real
# bar the candidate must clear, and so higher levels dominate the weighted mix.
#
# Bars (0-100): the raw skill score we expect from a candidate who genuinely
# operates at that level. Scores at or above the bar earn full credit; scores
# below the bar are penalised with a quadratic curve (small gaps shave a little,
# wide gaps shave a lot). See _apply_qualifier_curve below.
_QUALIFIER_BARS: dict[str, int] = {
    "basic":        45,   # any credible mention — easy to clear
    "intermediate": 65,   # declared + some real usage
    "advanced":     80,   # declared + sustained, recent experience
    "expert":       90,   # declared + multi-year primary experience
    "any":          0,    # no bar — raw score is used as-is
}

# Weight each qualifier carries in the requirement-level average. An EXPERT
# requirement is worth ~4 INTERMEDIATE ones, so a "must have expert Java" miss
# moves the final job_fit_score far more than a "basic Git" miss.
_QUALIFIER_WEIGHTS: dict[str, float] = {
    "basic":        1.0,
    "intermediate": 1.5,
    "advanced":     2.5,
    "expert":       4.0,
    "any":          1.0,
}

# Below this raw score, an ADVANCED or EXPERT requirement is flagged as a
# critical gap — the candidate cannot plausibly perform the role at that level.
_CRITICAL_GAP_FLOOR = 50


def _apply_qualifier_curve(raw_score: int, qualifier: str) -> int:
    """Translate a raw skill score (0-100) into the effective score actually
    consumed by the weighted average, based on the required qualifier level.

      • raw_score ≥ bar  → effective = raw_score  (full credit, no inflation)
      • raw_score < bar  → effective = bar × (raw/bar)²
        ‑ small gap (raw close to bar) ≈ a few points off
        ‑ wide gap (raw ≪ bar)        ≈ collapses fast (quadratic)

    The squared curve is what makes "BASIC Git at 60" and "EXPERT Java at 60"
    score very differently: the former is above its bar (45) so it gets 60
    intact; the latter is far below 90 so it drops to ~27.
    """
    if raw_score <= 0:
        return 0
    bar = _QUALIFIER_BARS.get(qualifier, 0)
    if bar <= 0 or raw_score >= bar:
        return raw_score
    ratio = raw_score / bar
    return round(bar * ratio * ratio)


def _qualifier_signal(raw_score: int, qualifier: str) -> str:
    """Human-readable signal label for one skill given the required level.

    Returned values feed the UI so the recruiter can see, at a glance, whether
    a candidate clears each bar — not just whether the overall job_fit_score
    is high.
    """
    bar = _QUALIFIER_BARS.get(qualifier, 0)
    if bar <= 0:
        return ""  # "any" qualifier — no signal worth reporting
    if raw_score >= bar:
        if qualifier in ("advanced", "expert"):
            return "strength"
        return "meets"
    if qualifier in ("advanced", "expert") and raw_score < _CRITICAL_GAP_FLOOR:
        return "critical_gap"
    return "gap"

# ── Advisory warnings (non-scoring signals shown to the recruiter) ────────────
#
# Two checks run on every match and surface into SemanticMatchResult.warnings:
#   • distance_far  — ON_SITE job and candidate lives outside daily-commute range
#   • name_mismatch — CV / GitHub / LinkedIn display names don't agree
#
# These never affect the score. They show up as banners on the recruiter side
# so they can probe the discrepancy in the interview.

# Cities/governorates close enough for a daily commute to VERMEG HQ (Lac 1).
# Greater Tunis spans 4 governorates that share urban transport with the capital:
# Tunis, Ariana, Ben Arous, Manouba. Everything else (Sousse, Sfax, Bizerte,
# Nabeul, Hammamet, …) sits 1-3 hours away and breaks daily on-site work.
_TUNIS_AREA_LOCATIONS = {
    # Tunis governorate
    "tunis", "la marsa", "marsa", "carthage", "sidi bou said", "le bardo", "bardo",
    "le kram", "kram", "goulette", "la goulette", "el menzah", "menzah",
    "el manar", "manar", "centre urbain nord", "berges du lac", "lac",
    "lac 1", "lac 2", "lac1", "lac2", "el omrane", "omrane", "bab souika",
    "bab bhar", "medina", "hrairia", "sijoumi", "sidi hassine",
    # Ariana governorate
    "ariana", "raoued", "la soukra", "soukra", "borj louzir", "ettadhamen",
    "mnihla", "kalaat el andalous", "sidi thabet",
    # Ben Arous governorate
    "ben arous", "rades", "el mourouj", "mourouj", "hammam lif", "hammam-lif",
    "megrine", "mohamedia", "mohammedia", "fouchana", "ezzahra", "bou mhel",
    # Manouba governorate
    "manouba", "la manouba", "den den", "douar hicher", "oued ellil",
    "tebourba", "borj el amri",
}

def _normalize_location_tokens(text: Optional[str]) -> set[str]:
    """Lowercase, strip accents, and split a free-form location into tokens
    plus 2-3 word phrases. Lets us match 'Lac 1, Tunis' against the whitelist."""
    if not text:
        return set()
    s = text.lower()
    # Strip accents (é→e, à→a, …) so 'Tūnis' / 'Tunis' both normalize
    s = ''.join(c for c in __import__('unicodedata').normalize('NFKD', s)
                if not __import__('unicodedata').combining(c))
    s = re.sub(r"[^a-z0-9\s]+", " ", s)
    s = re.sub(r"\s+", " ", s).strip()
    words = s.split()
    tokens: set[str] = set(words)
    # Multi-word phrases ("la marsa", "ben arous") — try 2- and 3-word sliding windows
    for n in (2, 3):
        for i in range(len(words) - n + 1):
            tokens.add(" ".join(words[i:i+n]))
    return tokens

def _check_distance_warning(cv_location: Optional[str], work_arrangement: Optional[str]) -> Optional[dict]:
    """Emit a warning when the job is ON_SITE and the candidate lives far from
    VERMEG HQ. Silent for HYBRID/REMOTE (commute doesn't matter there). Silent
    when the CV location is missing — absence isn't evidence of distance."""
    if (work_arrangement or "").upper() != "ON_SITE":
        return None
    if not cv_location:
        return None
    tokens = _normalize_location_tokens(cv_location)
    if not tokens:
        return None
    if tokens & _TUNIS_AREA_LOCATIONS:
        return None  # candidate sits inside Greater Tunis — no warning
    return {
        "kind":     "distance_far",
        "severity": "warning",
        "message":  f"Candidate location ({cv_location.strip()}) is outside the Greater Tunis area — "
                    f"daily on-site commute to VERMEG HQ (Lac 1) may not be feasible.",
        "details":  {"cv_location": cv_location.strip(), "work_arrangement": "ON_SITE"},
    }

def _normalize_person_name(name: Optional[str]) -> set[str]:
    """Lowercase, strip accents, drop short tokens (initials, particles).
    Returns the set of significant name tokens for comparison."""
    if not name:
        return set()
    import unicodedata
    s = ''.join(c for c in unicodedata.normalize('NFKD', name.lower())
                if not unicodedata.combining(c))
    s = re.sub(r"[^a-z\s-]+", " ", s)
    # Drop name particles that carry no identifying signal
    drop = {"de", "la", "le", "el", "al", "ben", "bin", "ibn", "du", "da", "van", "von"}
    return {t for t in s.replace("-", " ").split() if len(t) >= 3 and t not in drop}

def _names_disagree(a: set[str], b: set[str]) -> bool:
    """Two name token sets disagree when they share NO significant tokens."""
    if not a or not b:
        return False  # missing data is not a mismatch
    return len(a & b) == 0

def _extract_linkedin_handle(url: Optional[str]) -> Optional[str]:
    """Pull the username slug from a linkedin.com/in/<slug>/ URL."""
    if not url:
        return None
    m = re.search(r"linkedin\.com/in/([^/?#]+)", url, re.IGNORECASE)
    if not m:
        return None
    # Slugs usually look like 'selim-lassoued-abc123' — turn dashes into spaces
    return m.group(1).replace("-", " ").replace("_", " ").strip()

def _check_name_warnings(cv) -> list[dict]:
    """Compare CV display name vs GitHub display name vs LinkedIn handle/name.
    Only fires when both sides of a comparison are populated and share no
    significant tokens. Missing data is silently skipped."""
    cv_name     = (cv.candidate_name or "").strip() or None
    gh          = getattr(cv, "github_profile", None)
    gh_name     = (gh.name if gh and gh.name else None)
    gh_username = (gh.username if gh and gh.username else None)
    li_url      = None
    if getattr(cv, "social_links", None):
        li_url = cv.social_links.linkedin
    if not li_url and getattr(cv, "linkedin_enrichment", None):
        li_url = cv.linkedin_enrichment.profile_url
    li_handle   = _extract_linkedin_handle(li_url)

    cv_tokens = _normalize_person_name(cv_name)
    gh_tokens = _normalize_person_name(gh_name) or _normalize_person_name(gh_username)
    li_tokens = _normalize_person_name(li_handle)

    warnings: list[dict] = []
    if _names_disagree(cv_tokens, gh_tokens):
        warnings.append({
            "kind":     "name_mismatch",
            "severity": "warning",
            "message":  f"Name on CV ('{cv_name}') doesn't match GitHub profile name "
                        f"('{gh_name or gh_username}'). Verify identity before interview.",
            "details":  {"source": "github", "cv_name": cv_name,
                         "other": gh_name or gh_username},
        })
    if _names_disagree(cv_tokens, li_tokens):
        warnings.append({
            "kind":     "name_mismatch",
            "severity": "warning",
            "message":  f"Name on CV ('{cv_name}') doesn't match LinkedIn handle "
                        f"('{li_handle}'). Verify identity before interview.",
            "details":  {"source": "linkedin", "cv_name": cv_name, "other": li_handle},
        })
    if _names_disagree(gh_tokens, li_tokens):
        warnings.append({
            "kind":     "name_mismatch",
            "severity": "warning",
            "message":  f"GitHub profile ('{gh_name or gh_username}') and LinkedIn handle "
                        f"('{li_handle}') disagree on the candidate's name.",
            "details":  {"source": "github_vs_linkedin",
                         "github": gh_name or gh_username, "linkedin": li_handle},
        })
    return warnings


_OR_SPLIT_RE  = re.compile(r"\s+or\s+",  re.IGNORECASE)
_AND_SPLIT_RE = re.compile(r"\s+and\s+", re.IGNORECASE)

# Minimum semantic similarity for a proxy skill to prove a concept requirement
PROXY_SIM_THRESHOLD = 0.63

# Phrases containing these words are education/HR requirements, not technical skills
_EDUCATION_KEYWORDS = {
    "bachelor", "master", "licence", "license", "degree", "diploma",
    "phd", "bsc", "msc", "mba", "bac", "formation", "graduate",
    "university", "school", "engineering", "certification",
}

# Known multi-word technical skills that must stay together.
# Without this list, "Linux command line" splits into ["linux", "command"],
# and "command" alone gets scored as if it were a real skill — producing
# meaningless 15-20% bars on the candidate breakdown.
_MULTI_WORD_SKILLS = {
    "spring boot", "machine learning", "deep learning", "natural language",
    "computer vision", "data science", "big data", "node.js", "react.js",
    "vue.js", "angular.js", "rest api", "web services", "agile methodology",
    "ci/cd", "test driven", "object oriented", "design patterns",
    "responsive design", "web design", "ui design", "ux design",
    "ui/ux", "power bi", "sql server", "pl/sql", "stored procedures",
    "unit testing", "integration testing", "test automation",
    "clean code", "clean architecture", "domain driven", "event driven",
    # OS / infra phrases recruiters write but that don't survive tokenization:
    "linux command line", "command line", "shell scripting", "bash scripting",
    "version control", "source control", "system design", "system architecture",
    "software architecture", "cloud computing", "distributed systems",
    "object oriented programming", "functional programming",
    "ci cd pipelines", "ci/cd pipelines", "rest api design",
    "api design", "microservices architecture",
}

# Tokens that look like version numbers or qualifiers — never a skill name
_VERSION_RE = re.compile(r'^v?\d[\d+.*x-]*$')

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
    # Strip version numbers attached to skill names: "Angular 16+", "Node 18.x", "React v18"
    s = re.sub(r"\s+v?\d[\d+.*x-]*", "", s)
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
        if len(w) >= 2 and w not in all_stop and not _VERSION_RE.match(w):
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


class SkillSources:
    """Tracks where each skill was found in the CV plus per-skill year/duration metadata,
    so confidence scores can apply a recency decay and a duration boost."""
    def __init__(self, skill_intel: Optional[dict] = None):
        self.primary:    set[str] = set()   # cv.skills (main declared section)
        self.experience: set[str] = set()   # exp.skills_used
        self.project:    set[str] = set()   # proj.skills_used
        self.github_confirmed: set[str] = set()
        self.github_detected:  set[str] = set()
        # Per-skill metadata (keys are normalized skill names):
        self.exp_year:     dict[str, int]   = {}   # latest year worked, from experience
        self.exp_duration: dict[str, float] = {}   # cumulative years across jobs
        self.proj_year:    dict[str, int]   = {}   # latest year worked, from a project
        # Implied-skills tracking (Angular implies TypeScript, Spring implies Java, …):
        self.explicit_skills: set[str] = set()   # tokens the candidate explicitly listed
        self.implied_by:      dict[str, str] = {}   # token -> framework name that implied it
        self.declared_literal: set[str] = set()  # tokens LITERALLY in cv.skills / cv.knowledge
        self.implied_primary:  set[str] = set()  # in primary tier ONLY via framework implication
        # LLM-derived per-skill intel: {skill_lower: {volatility, implies}}. Generic;
        # populated upstream by skill_intel.get_skill_intelligence(). Consulted by
        # _tech_half_life() and the implies-expansion in _expand_experience/_expand_project.
        self.skill_intel: dict = skill_intel or {}

    @property
    def all_skills(self) -> set[str]:
        return (self.primary | self.experience | self.project
                | self.github_confirmed | self.github_detected)

    def record_experience(self, normalized: str, year: Optional[int], duration: float) -> None:
        if not normalized:
            return
        self.experience.add(normalized)
        if year is not None and year > 0:
            self.exp_year[normalized] = max(self.exp_year.get(normalized, 0), year)
        if duration > 0:
            self.exp_duration[normalized] = self.exp_duration.get(normalized, 0.0) + duration

    def record_project(self, normalized: str, year: Optional[int]) -> None:
        if not normalized:
            return
        self.project.add(normalized)
        if year is not None and year > 0:
            self.proj_year[normalized] = max(self.proj_year.get(normalized, 0), year)

    def merge_github_floor(self, normalized: str, last_used: Optional[int], years: float) -> None:
        """GitHub tech_stats sets a FLOOR on year/duration for skills already in experience or
        projects. Does NOT promote GitHub-only skills to experience tier — that stays bonus-only."""
        if not normalized:
            return
        if normalized in self.experience:
            if last_used:
                self.exp_year[normalized] = max(self.exp_year.get(normalized, 0), last_used)
            if years > 0:
                self.exp_duration[normalized] = max(self.exp_duration.get(normalized, 0.0), float(years))
        elif normalized in self.project:
            if last_used:
                self.proj_year[normalized] = max(self.proj_year.get(normalized, 0), last_used)

    def confidence(self, skill: str) -> int:
        """
        Source-weighted confidence (0-100) with duration boost and recency decay
        applied to evidence components only. The primary-declaration base (70) is
        unaffected — the candidate is currently claiming the skill.

          Base:
            cv.skills (main section)           → 70  (flat, no decay)
            exp.skills_used only               → 50 × duration_boost × recency
            proj.skills_used only              → 40 × recency
          Bonuses (corroborating evidence):
            primary + experience               → +15 × duration_boost × recency
            primary + project                  → +8  × recency
          GitHub bonus (external validation, flat):
            confirmed                          → +5
            detected                           → +3
        """
        n = _normalize(skill)

        in_primary = n in self.primary
        in_exp     = n in self.experience
        in_proj    = n in self.project

        if in_primary:
            score = 70.0
        elif in_exp:
            duration = self.exp_duration.get(n, 0.0)
            year     = self.exp_year.get(n)
            score = 50.0 * _duration_boost(duration) * _recency_factor(n, year, self.skill_intel)
        elif in_proj:
            year  = self.proj_year.get(n)
            score = 40.0 * _recency_factor(n, year, self.skill_intel)
        else:
            return 0

        if in_primary and in_exp:
            duration = self.exp_duration.get(n, 0.0)
            year     = self.exp_year.get(n)
            score += 15.0 * _duration_boost(duration) * _recency_factor(n, year, self.skill_intel)
        if in_primary and in_proj:
            year   = self.proj_year.get(n)
            score += 8.0 * _recency_factor(n, year, self.skill_intel)

        if n in self.github_confirmed:
            score += 5
        elif n in self.github_detected:
            score += 3

        return min(int(round(score)), 100)

    def experience_only_credit(self, skill: str) -> int:
        """Score 1 for proxy matching — counts experience/project/GitHub but never the
        primary declaration (candidate didn't claim the concept directly).
        Same recency/duration treatment as confidence()."""
        n = _normalize(skill)
        score = 0.0
        if n in self.experience:
            duration = self.exp_duration.get(n, 0.0)
            year     = self.exp_year.get(n)
            score = 50.0 * _duration_boost(duration) * _recency_factor(n, year, self.skill_intel)
        elif n in self.project:
            year  = self.proj_year.get(n)
            score = 40.0 * _recency_factor(n, year, self.skill_intel)

        if n in self.github_confirmed:
            score += 5
        elif n in self.github_detected:
            score += 3
        return min(int(round(score)), 100)


def _expand_tokens(value: Optional[str]) -> set[str]:
    """Expand a single skill value into normalized tokens (the same set that
    _expand_into would have added). Centralized so experience/project records can
    apply the same expansion while also carrying year/duration metadata."""
    n = _normalize(value)
    if not n or len(n) <= 1:
        return set()
    tokens: set[str] = {n}
    words = n.split()
    if len(words) > 1:
        for w in words:
            if len(w) >= 3 and w not in _STOPWORDS:
                tokens.add(w)
        for i in range(len(words) - 1):
            tokens.add(f"{words[i]} {words[i+1]}")
    for delimiter in ['/', '-', '.']:
        if delimiter in n:
            for part in n.split(delimiter):
                if len(part) >= 2 and part not in _STOPWORDS:
                    tokens.add(part)
    for suffix in ['sql', 'db', 'js', 'py', 'ml']:
        if n.endswith(suffix) and len(n) > len(suffix) + 1:
            tokens.add(suffix)
    return tokens


def _expand_into(target: set[str], values: Optional[Iterable[Optional[str]]]) -> None:
    """Expand skill values into normalized tokens and add to target set."""
    for v in (values or []):
        for tok in _expand_tokens(v):
            target.add(tok)


def _implied_techs(sources: SkillSources, normalized: str) -> list[str]:
    """Return the technologies a framework strictly requires (e.g. Angular →
    TypeScript/HTML/CSS, Spring Boot → Java).

    Source: the LLM-derived per-skill classification in skill_intel.py, computed
    once per skill and cached to a persistent store. Fully generic — works for
    ANY framework, including ones unknown to this codebase.

    If a skill has no skill_intel entry (the classifier was unavailable the first
    time it was seen), returns [] — the skill simply gets no implication
    expansion on that run, and will be classified + cached on a later run."""
    entry = sources.skill_intel.get(normalized) if sources.skill_intel else None
    if isinstance(entry, dict):
        implies = entry.get("implies")
        if isinstance(implies, list):
            return [str(x) for x in implies if isinstance(x, str) and x]
    return []


def _expand_experience(sources: SkillSources,
                       values: Optional[Iterable[Optional[str]]],
                       year: Optional[int],
                       duration: float) -> None:
    """Add experience skills with per-token year/duration metadata.
    For each framework value (e.g. "Angular"), also adds its implied
    technologies (TypeScript, JS, HTML, CSS) with the SAME year/duration."""
    for v in (values or []):
        normalized = _normalize(v)
        # 1) Original skill — explicit
        for tok in _expand_tokens(v):
            sources.record_experience(tok, year, duration)
        # 2) Implied techs — recorded as implied (only if not explicitly stated elsewhere)
        for implied in _implied_techs(sources, normalized):
            for tok in _expand_tokens(implied):
                sources.record_experience(tok, year, duration)
                if tok not in sources.explicit_skills:
                    sources.implied_by.setdefault(tok, normalized)


def _expand_project(sources: SkillSources,
                    values: Optional[Iterable[Optional[str]]],
                    year: Optional[int]) -> None:
    """Add project skills with per-token year metadata + implied techs."""
    for v in (values or []):
        normalized = _normalize(v)
        for tok in _expand_tokens(v):
            sources.record_project(tok, year)
        for implied in _implied_techs(sources, normalized):
            for tok in _expand_tokens(implied):
                sources.record_project(tok, year)
                if tok not in sources.explicit_skills:
                    sources.implied_by.setdefault(tok, normalized)


def _expand_primary(sources: SkillSources,
                    values: Optional[Iterable[Optional[str]]]) -> None:
    """Add declared skills to the primary set. For each framework value
    (e.g. "Angular"), also adds its implied technologies (TypeScript, HTML, CSS)
    to the SAME primary tier — declaring a framework declares its required stack:
    you cannot do Angular without TypeScript/HTML/CSS, Spring Boot without Java, etc."""
    for v in (values or []):
        normalized = _normalize(v)
        for tok in _expand_tokens(v):
            sources.primary.add(tok)
        for implied in _implied_techs(sources, normalized):
            for tok in _expand_tokens(implied):
                sources.primary.add(tok)
                if tok not in sources.explicit_skills:
                    sources.implied_by.setdefault(tok, normalized)
                if tok not in sources.declared_literal:
                    sources.implied_primary.add(tok)


def _build_skill_sources(cv: CvAnalysisResult) -> SkillSources:
    """Build source-aware skill sets from the CV, with year/duration metadata
    parsed from work_experience.duration and floored by GitHub tech_stats when
    available. Framework→language implications expand EACH tier so e.g. listing
    "Angular" in CV Skills also credits TypeScript/HTML/CSS at the declared tier,
    and using "Angular" in a job credits them at the experience tier."""
    sources = SkillSources(skill_intel=getattr(cv, "skill_intel", None) or {})

    # ── Pre-pass: collect every EXPLICITLY listed skill token FIRST, so the
    # implication logic below can distinguish "candidate stated this" vs
    # "framework implies this" (drives the "(implied by …)" reason text).
    for src in (cv.skills or []):
        for tok in _expand_tokens(src):
            sources.explicit_skills.add(tok)
            sources.declared_literal.add(tok)   # literally in CV Skills section
    for src in (cv.knowledge or []):
        for tok in _expand_tokens(src):
            sources.explicit_skills.add(tok)
            sources.declared_literal.add(tok)   # literally declared (secondary)
    for exp in (cv.work_experience or []):
        for v in (exp.skills_used or []):
            for tok in _expand_tokens(v): sources.explicit_skills.add(tok)
    for proj in (cv.projects or []):
        for v in (proj.skills_used or []):
            for tok in _expand_tokens(v): sources.explicit_skills.add(tok)

    # ── Primary (declared) skills + framework implications at declared tier ──
    _expand_primary(sources, cv.skills)
    _expand_primary(sources, cv.knowledge)  # knowledge = secondary declared skills

    # ── Work experience: parse duration string for year + cumulative years ───
    for exp in (cv.work_experience or []):
        year, duration = _parse_duration_info(exp.duration)
        _expand_experience(sources, exp.skills_used, year, duration)

    # ── Projects: try to parse a year from the description (no duration field) ─
    for proj in (cv.projects or []):
        year, _ = _parse_duration_info(proj.description)
        _expand_project(sources, proj.skills_used, year)

    # ── Hackathons: treated as small experience entries ──────────────────────
    for hack in (cv.hackathons or []):
        year, _ = _parse_duration_info(hack.date)
        _expand_experience(sources, hack.skills_used, year, 0.1)

    if cv.github_profile:
        _expand_into(sources.github_confirmed, cv.github_profile.cv_skills_confirmed)
        _expand_into(sources.github_detected,  cv.github_profile.cv_skills_likely)
        _expand_into(sources.github_detected,  cv.github_profile.all_technologies)
        _expand_into(sources.github_detected,  cv.github_profile.all_repo_frameworks)

        # ── GitHub tech_stats: floor on year/duration for already-known skills ─
        # We DON'T promote GitHub-only skills to experience tier — that stays
        # bonus-only by design (see "GitHub as bonus-only prevents gaming").
        for tech, stats in (cv.github_profile.tech_stats or {}).items():
            last_used = int(stats.get("last_used") or 0)
            years     = float(stats.get("years") or 0.0)
            for tok in _expand_tokens(tech):
                sources.merge_github_floor(tok, last_used or None, years)

        # ── Implicit "uses git" credit ─────────────────────────────────────────
        # Owning a public, currently-active GitHub profile IS a declaration of
        # git usage — you literally cannot publish code to GitHub without it.
        # We credit `git` at BOTH the primary tier (base 70, since the GitHub
        # link in the CV is a declared signal) AND the experience tier (so the
        # primary+experience corroboration bonus +15 fires too). Combined with
        # the +5 GitHub bonus this lands the score around 85-90% — appropriate
        # for someone visibly using git in production.
        gh = cv.github_profile
        has_real_activity = (
            (gh.own_repos_count or 0) > 0
            or (gh.real_repos_count or 0) > 0
            or bool(gh.username)
        )
        if has_real_activity and "git" not in sources.experience:
            # Primary tier: GitHub presence acts as an implicit declaration.
            # We do NOT add to declared_literal (it wasn't literally typed in
            # the Skills section) — but `implied_primary` correctly tags it as
            # "implied by GitHub, not directly written by the candidate".
            sources.primary.add("git")
            sources.implied_primary.add("git")

            # Experience tier: keeps the +15 corroboration bonus + duration/recency.
            sources.experience.add("git")
            sources.implied_by.setdefault("git", "github")
            # Estimate years from account age (capped) so recency stays sensible.
            years_on_gh = max(0.5, min(float((gh.account_age_days or 0) / 365.0), 10.0))
            sources.exp_duration["git"] = max(sources.exp_duration.get("git", 0.0), years_on_gh)
            # Recency: if last_active is parseable, take its year; else use the
            # current year (any activity at all proves they're still git-active).
            last_year = _CURRENT_YEAR
            if gh.last_active:
                try:
                    last_year = int(gh.last_active[:4])
                except (ValueError, TypeError):
                    pass
            sources.exp_year["git"] = max(sources.exp_year.get("git", 0), last_year)
            # Also mark it as github-confirmed so the +5 bonus and the
            # "GitHub Verified" evidence tag flow naturally.
            sources.github_confirmed.add("git")

    return sources




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


_logger = logging.getLogger(__name__)


def _get_or_compute_job_vector(
    job_id: Optional[str],
    job_text: str,
    cache: dict[str, list[float]],
) -> list[float]:
    """Return the job's embedding, using the persisted cache when possible.

    Flow:
      1. If `job_id` is None → can't reach the cache → compute via Ollama.
      2. GET /api/jobs/{id}/embedding from job-microservice.
         - 200 + matching model → use the stored vector. ZERO Ollama cost.
         - 204 (no cache yet) → compute via Ollama, then PUT it back so future
           applicants reuse it.
         - any other status / network error → log + fall back to Ollama.
      3. The in-request `cache` dict still memoizes within one request so we
         never PUT the same vector twice for the same match.
    """
    if not job_id:
        _logger.info("[job-emb] job_id missing, embedding inline (no cache).")
        return _embed_cached(job_text, cache)

    cache_key = f"__job_cache::{job_id}"
    if cache_key in cache:
        return cache[cache_key]

    url = f"{JOB_SERVICE_URL}/api/jobs/{job_id}/embedding"

    # Try the persistent cache first.
    try:
        resp = requests.get(url, timeout=5)
        if resp.status_code == 200:
            payload = resp.json() or {}
            stored_model = payload.get("model")
            vec          = payload.get("embedding") or []
            if stored_model == EMBEDDING_MODEL and len(vec) == 768:
                vec = [float(x) for x in vec]
                cache[cache_key] = vec
                _logger.info("[job-emb] HIT for %s (model=%s).", job_id, stored_model)
                return vec
            _logger.info(
                "[job-emb] STALE for %s (model=%s, dim=%d); recomputing.",
                job_id, stored_model, len(vec),
            )
        elif resp.status_code == 204:
            _logger.info("[job-emb] MISS for %s; computing.", job_id)
        else:
            _logger.warning("[job-emb] Unexpected status %s for %s.", resp.status_code, job_id)
    except Exception as e:
        _logger.warning("[job-emb] Read failed for %s: %s", job_id, e)

    # Compute fresh.
    vec = _embed_cached(job_text, cache)
    cache[cache_key] = vec

    # Best-effort write-back. Don't fail the match if persistence fails — the
    # match still produces a correct score from the freshly computed vector.
    if vec and len(vec) == 768:
        try:
            put_resp = requests.put(
                url,
                json={"embedding": vec, "model": EMBEDDING_MODEL},
                timeout=10,
            )
            if put_resp.status_code in (200, 204):
                _logger.info("[job-emb] STORED for %s (%d dims).", job_id, len(vec))
            else:
                _logger.warning("[job-emb] PUT got status %s body=%s",
                                put_resp.status_code, put_resp.text[:200])
        except Exception as e:
            _logger.warning("[job-emb] Write failed for %s: %s", job_id, e)
    else:
        _logger.warning("[job-emb] Vector wrong size (%d), skipping PUT for %s",
                        len(vec) if vec else 0, job_id)

    return vec


def _prefetch_requirement_vectors(job_id: Optional[str]) -> dict[str, list[float]]:
    """Bulk-fetch all cached requirement embeddings for a job at the start of
    a match request. Returns a {requirement_id: vector} map (empty on miss or
    error). The matcher consults this map before calling Ollama for each
    requirement and writes back any newly-computed vectors individually.
    """
    if not job_id:
        return {}
    url = f"{JOB_SERVICE_URL}/api/jobs/{job_id}/requirement-embeddings"
    try:
        resp = requests.get(url, timeout=5)
        if resp.status_code != 200:
            _logger.info("[req-emb] prefetch got %s for %s", resp.status_code, job_id)
            return {}
        out: dict[str, list[float]] = {}
        for entry in (resp.json() or []):
            rid   = entry.get("requirementId")
            model = entry.get("model")
            vec   = entry.get("embedding") or []
            if rid and model == EMBEDDING_MODEL and len(vec) == 768:
                out[str(rid)] = [float(x) for x in vec]
        _logger.info("[req-emb] prefetched %d cached req vectors for %s", len(out), job_id)
        return out
    except Exception as e:
        _logger.warning("[req-emb] prefetch failed for %s: %s", job_id, e)
        return {}


def _get_or_compute_req_vector(
    job_id: Optional[str],
    req_id: Optional[str],
    req_text: str,
    cache: dict[str, list[float]],
    prefetched: dict[str, list[float]],
) -> list[float]:
    """Return a requirement's embedding, prefer the persistent cache.

    Lookup order:
      1. The pre-fetched map (one batch GET at match start).
      2. The in-request memo (avoid duplicate Ollama calls within one match).
      3. Compute via Ollama, store both in the in-request memo and PUT to
         the persistent cache so the next applicant gets it for free.
    Falls back to a plain inline embed if job_id or req_id is missing.
    """
    if not job_id or not req_id:
        return _embed_cached(req_text, cache)

    rid = str(req_id)
    if rid in prefetched:
        return prefetched[rid]

    cache_key = f"__req_cache::{rid}"
    if cache_key in cache:
        return cache[cache_key]

    # Cache miss — compute.
    vec = _embed_cached(req_text, cache)
    cache[cache_key] = vec
    prefetched[rid] = vec   # so a repeat lookup in this request also hits

    if vec and len(vec) == 768:
        url = f"{JOB_SERVICE_URL}/api/jobs/{job_id}/requirements/{rid}/embedding"
        try:
            put_resp = requests.put(
                url,
                json={"embedding": vec, "model": EMBEDDING_MODEL},
                timeout=10,
            )
            if put_resp.status_code not in (200, 204):
                _logger.warning("[req-emb] PUT %s got %s", rid, put_resp.status_code)
        except Exception as e:
            _logger.warning("[req-emb] Write failed for %s: %s", rid, e)

    return vec


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


# Rescaling window for cosine similarity → [0, 1].
#
# IMPORTANT: _normalize_cosine is applied to SKILL-vs-DOCUMENT comparisons
# (a 1-2 word skill embedded against a full ~4000-char CV, or a job vs a CV).
# These cosines are structurally MUCH lower than skill-vs-skill pairs:
#   - skill-vs-skill (react/reactjs):        matched ≈ 0.81, unrelated ≈ 0.48
#   - skill-vs-document (angular vs CV):     strong ≈ 0.60-0.70, unrelated ≈ 0.35-0.45
# A short query embedded against a long document never reaches high cosine —
# document length dilutes the similarity. So we rescale the *document* window
# [_COSINE_FLOOR, _COSINE_CEIL] → [0, 1]:
#   - below FLOOR  → 0.0  (unrelated)
#   - above CEIL   → 1.0  (strongly present)
# (Do NOT reuse _CALIBRATION_PAIRS thresholds here — those are skill-vs-skill.)
_COSINE_FLOOR = 0.35   # at/below this, the skill is effectively absent
_COSINE_CEIL  = 0.78   # at/above this, the skill is strongly present in the doc


def _normalize_cosine(cos: float) -> float:
    """Rescale a skill-vs-document cosine into [0, 1] over the document window.
    cos ≤ 0.35 → 0.0  (unrelated)
    cos = 0.55 → 0.47 (moderately present)
    cos = 0.65 → 0.70 (clearly present)
    cos ≥ 0.78 → 1.0  (strongly present)"""
    return max(0.0, min((cos - _COSINE_FLOOR) / (_COSINE_CEIL - _COSINE_FLOOR), 1.0))


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

    # ── Structured degree_level — recruiter dropdown (may be MULTI-SELECT) ───
    # degree_level can carry several comma/pipe-separated values, e.g.
    # "LICENCE_BACHELOR,ENGINEER,MASTER" — the recruiter accepts ANY of them.
    # ENGINEER (ingénieur, bac+5) ranks with MASTER, above LICENCE.
    _STRUCTURED_DEGREE_MAP = {
        "BAC": 0, "BTS_DUT": 1, "LICENCE_BACHELOR": 2,
        "ENGINEER": 3, "MASTER": 3, "PHD": 4, "ANY": None,
    }
    structured_req_level: Optional[int] = None
    structured_is_any = False
    if degree_level:
        selected = [d.strip().upper() for d in re.split(r"[,|/]+", degree_level) if d.strip()]
        if "ANY" in selected or "NONE" in selected:
            structured_is_any = True
        else:
            levels = [_STRUCTURED_DEGREE_MAP[d] for d in selected
                      if d in _STRUCTURED_DEGREE_MAP and _STRUCTURED_DEGREE_MAP[d] is not None]
            if levels:
                # Recruiter accepts any of the ticked degrees → the bar is the
                # LOWEST one (a higher degree always satisfies a lower bar too).
                structured_req_level = min(levels)

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
    req_level: Optional[int]
    if structured_is_any:
        req_level = None  # recruiter ticked "Any degree" — any education accepted
    elif structured_req_level is not None:
        req_level = structured_req_level
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


# ── Language matching ─────────────────────────────────────────────────────────
# Proficiency levels on a single numeric scale (higher = more proficient).
# Covers CEFR codes plus the word-based levels CVs use, in EN/FR/ES/IT.
_LANGUAGE_LEVELS: dict[str, int] = {
    # CEFR
    "a1": 1, "a2": 2, "b1": 3, "b2": 4, "c1": 5, "c2": 6,
    # Word-based — English
    "beginner": 1, "basic": 1, "notions": 1, "elementary": 2, "pre-intermediate": 2,
    "intermediate": 3, "upper-intermediate": 4,
    "advanced": 5, "fluent": 5, "proficient": 6, "proficiency": 6,
    "native": 7, "bilingual": 7, "mother": 7,
    # Word-based — French
    "debutant": 1, "elementaire": 2, "intermediaire": 3,
    "avance": 5, "courant": 5, "bilingue": 7, "maternelle": 7, "natif": 7,
    # Word-based — Spanish / Italian
    "principiante": 1, "intermedio": 3, "avanzado": 5, "nativo": 7,
}

# Language name → canonical key. Keys are accent-folded lowercase (see _fold).
_LANGUAGE_ALIASES: dict[str, str] = {
    "english": "english", "anglais": "english", "ingles": "english", "inglese": "english",
    "french": "french", "francais": "french", "frances": "french", "francese": "french",
    "arabic": "arabic", "arabe": "arabic", "arabo": "arabic",
    "spanish": "spanish", "espagnol": "spanish", "espanol": "spanish", "spagnolo": "spanish",
    "german": "german", "allemand": "german", "deutsch": "german", "tedesco": "german",
    "italian": "italian", "italien": "italian", "italiano": "italian",
    "portuguese": "portuguese", "portugais": "portuguese",
    "chinese": "chinese", "chinois": "chinese", "mandarin": "chinese",
    "japanese": "japanese", "japonais": "japanese",
    "russian": "russian", "russe": "russian",
}

_ACCENT_MAP = str.maketrans("àâäéèêëçîïôöùûü", "aaaeeeeciioouuu")


def _fold(s: Optional[str]) -> str:
    """Lowercase + strip accents — for accent-insensitive language-name matching."""
    return (s or "").lower().translate(_ACCENT_MAP)


def _canonical_language(name: Optional[str]) -> Optional[str]:
    """Map a language name in any of EN/FR/ES/IT to its canonical key."""
    folded = _fold(name)
    for alias, canon in _LANGUAGE_ALIASES.items():
        if alias in folded:
            return canon
    return None


def _parse_language_level(text: Optional[str]) -> Optional[int]:
    """Parse a proficiency level (CEFR code or word) from text. None if absent."""
    folded = _fold(text)
    if "upper" in folded and "intermediate" in folded:
        return 4
    tokens = set(re.split(r"[^a-z0-9-]+", folded))
    for key, val in _LANGUAGE_LEVELS.items():
        if key in tokens:
            return val
    return None


def _match_language_req(
    description: str,
    cv: CvAnalysisResult,
    language_level: Optional[str] = None,
) -> tuple[int, str]:
    """Match a LANGUAGE requirement against the candidate's languages.

    Compares proficiency on a unified scale (CEFR + word levels). The structured
    language_level field (A1-C2) takes priority over text parsing.
      candidate level ≥ required        → 100 matched
      candidate level == required − 1   → 65  partial (one level short)
      candidate level ≤ required − 2    → 25  missing
      language present, level unknown   → 60  partial (benefit of the doubt)
      language present, no level asked  → 100 matched
      language absent from CV           → 0   missing
    """
    if not cv.languages:
        return 0, "missing"

    required_lang  = _canonical_language(description)
    req_level      = _parse_language_level(language_level) or _parse_language_level(description)

    # Find the candidate's matching language (best level if several entries match)
    cand_found = False
    cand_level: Optional[int] = None
    for lang in cv.languages:
        canon = _canonical_language(lang.name)
        if required_lang is not None and canon != required_lang:
            continue
        cand_found = True
        lvl = _parse_language_level(lang.level)
        if lvl is not None:
            cand_level = max(cand_level or 0, lvl)

    if not cand_found:
        return 0, "missing"
    if req_level is None:
        return 100, "matched"          # language named, no level demanded
    if cand_level is None:
        return 60, "partial"           # has the language, level not stated
    if cand_level >= req_level:
        return 100, "matched"
    if cand_level == req_level - 1:
        return 65, "partial"
    return 25, "missing"


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
        in_skills_used = any(matches(s) for s in (exp.skills_used or []))
        in_description = matches(exp.description)
        if in_skills_used or in_description:
            company = exp.company or "Experience"
            year = ""
            if exp.duration:
                y = re.search(r"\d{4}", exp.duration)
                year = f" ({y.group()})" if y else ""
            if in_skills_used:
                tags.append(f"Experience: {company}{year}")
            else:
                tags.append(f"Inferred from: {company}{year}")
            break

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

def _skill_status(score: int, matched_threshold: float = SKILL_SEM_THRESHOLD) -> str:
    if score >= matched_threshold * 100: return "matched"
    if score >= SKILL_SEM_PARTIAL_THRESHOLD * 100: return "partial"
    return "missing"


def _evidence_suffix(sources: 'SkillSources', normalized: str, kind: str) -> str:
    """Build a `(~3yr, last used 2024)` style suffix for an evidence component
    when year/duration metadata is available. Empty string otherwise."""
    bits: list[str] = []
    if kind == "experience":
        duration = sources.exp_duration.get(normalized, 0.0)
        year     = sources.exp_year.get(normalized)
        if duration >= 1:
            bits.append(f"~{duration:.0f}yr")
        if year:
            years_since = max(0, _CURRENT_YEAR - year)
            if years_since == 0:
                bits.append("current")
            elif years_since == 1:
                bits.append("last year")
            else:
                bits.append(f"last used {year}")
    elif kind == "project":
        year = sources.proj_year.get(normalized)
        if year:
            years_since = max(0, _CURRENT_YEAR - year)
            if years_since <= 1:
                bits.append("recent")
            else:
                bits.append(f"from {year}")
    return f" ({', '.join(bits)})" if bits else ""


def _build_skill_reason(
    normalized: str,
    sources: 'SkillSources',
    mode: str,
    proxy: str | None = None,
    proxy_in_primary: bool = False,
) -> str:
    """Generate a human-readable explanation of why a skill received its score."""
    if mode == "DIRECT":
        parts: list[str] = []
        fw         = sources.implied_by.get(normalized)
        fw_display = fw.replace("-", " ").title() if fw else None

        if normalized in sources.declared_literal:
            # Literally listed in the CV Skills / knowledge section.
            parts.append("Declared in CV Skills")
            if normalized in sources.experience:
                parts.append("confirmed in work experience"
                             + _evidence_suffix(sources, normalized, "experience"))
            if normalized in sources.project:
                parts.append("confirmed in a project"
                             + _evidence_suffix(sources, normalized, "project"))
            if normalized in sources.github_confirmed:
                parts.append("GitHub Verified")
            elif normalized in sources.github_detected:
                parts.append("GitHub Detected")

        elif normalized in sources.implied_primary:
            # Not listed directly, but a DECLARED framework requires it
            # (e.g. Angular declared → TypeScript/HTML/CSS credited at declared tier).
            parts.append(f"Required by {fw_display or 'a declared framework'} "
                         f"— cannot be used without it")
            if normalized in sources.experience:
                parts.append("also used in work experience"
                             + _evidence_suffix(sources, normalized, "experience"))
            if normalized in sources.github_confirmed:
                parts.append("GitHub Verified")
            elif normalized in sources.github_detected:
                parts.append("GitHub Detected")

        else:
            # Experience / project tier (not declared, not implied by a declared fw).
            implied_note = (f" (implied by {fw_display})"
                            if fw and normalized not in sources.explicit_skills else "")
            if normalized in sources.experience:
                parts.append("Used in work experience" + implied_note
                             + _evidence_suffix(sources, normalized, "experience"))
            else:
                parts.append("Used in a project" + implied_note
                             + _evidence_suffix(sources, normalized, "project"))
            if not implied_note:
                parts.append("not declared in main CV Skills — score reduced")
        return " · ".join(parts)

    elif mode == "PROXY":
        display = (proxy or "related skill").replace("-", " ").title()
        if proxy_in_primary:
            # Distinguish a proxy that's literally on the CV from one that's
            # itself only implied by another skill. The latter is a two-hop
            # inference (e.g. Docker declared → Linux implied → "linux command
            # line" proxy-matched). Be honest with the recruiter so they know
            # what to probe in the interview.
            proxy_norm = _normalize(proxy or "")
            implied_only = (proxy_norm in sources.implied_primary
                            and proxy_norm not in sources.declared_literal)
            if implied_only:
                # Find the skill that implied this proxy so the chain is
                # readable: "inferred from X → Y" instead of just "implied by Y".
                source_fw = sources.implied_by.get(proxy_norm)
                source_fw_display = (source_fw.replace("-", " ").title()
                                     if source_fw else "a declared skill")
                return (f"Not listed directly — inferred via {source_fw_display} → {display} "
                        f"(two-step inference, discounted; verify in interview)")
            return f"Not listed directly — implied by {display} (declared in CV Skills)"
        else:
            return f"Not listed directly — inferred from {display} in experience · {display} not in CV Skills (partial credit)"

    else:  # SEMANTIC
        return "Not found in CV sources — score reflects semantic similarity from overall CV context"


def _compute_skill_score(
    req_skill: str,
    cv: CvAnalysisResult,
    cv_skills: set[str],
    sources: 'SkillSources',
    embed_cache: dict,
    cv_vec: list[float],
    matched_threshold: float = SKILL_SEM_THRESHOLD,
) -> tuple[int, str, str]:  # (score, status, reason)
    """
    Three-mode skill scoring — Score 1 and Score 2 are fully independent:

    DIRECT (Score1 > 0):
      Score1 = source confidence (cv.skills/exp/proj/github)
      Score2 = embed(skill) vs embed(full CV text)
      Blend  = 70% × Score1 + 30% × Score2

    PROXY (Score1=0, related skill in exp/proj with sim >= 0.70):
      Score1 = experience_only_credit of the proxy skill (sanction: no primary bonus)
      Score2 = embed(skill) vs embed(best experience/project description)
      Blend  = 10% × Score1 + 90% × Score2

    SEMANTIC ONLY (Score1=0, no proxy found):
      Score2 = embed(skill) vs embed(full CV text)
      Final  = 100% × Score2
    """
    normalized = _normalize(req_skill)
    if not normalized:
        return 0, "missing"

    req_vec = _embed_cached(normalized, embed_cache)

    # ── Score 1: Direct source evidence ───────────────────────────────────────
    score1 = sources.confidence(normalized)

    direct_final: int | None = None
    if score1 > 0:
        score2_direct = 0.0
        if req_vec:
            emb_sim       = _cosine(req_vec, cv_vec)
            score2_direct = _normalize_cosine(emb_sim) * 100
        # DIRECT mode: the skill is confirmed by HARD source evidence (declared
        # in CV / used in work experience / GitHub-verified). That evidence IS
        # the score. score2_direct is a single skill word embedded against the
        # whole CV document — a structurally low, noisy cosine (~0.35-0.45 even
        # for a strong match). It gets only a 10% contextual weight so it can
        # nudge but never override direct evidence.
        direct_final = round(0.90 * score1 + 0.10 * score2_direct)

    # ── Proxy: find a related skill confirmed in experience/project ────────────
    proxy_final: int | None = None
    best_proxy:  str | None = None
    proxy_in_primary = False

    if req_vec:
        best_sim = 0.0
        for cv_skill in cv_skills:
            # Was: `experience_only_credit == 0` — that skipped skills declared
            # only in the primary CV Skills section, silently hiding
            # transferable-knowledge proxies like "MySQL declared → some Postgres
            # credit". Use `confidence > 0` so primary-tier proxies count too.
            if sources.confidence(cv_skill) == 0:
                continue
            sim = _cosine(req_vec, _embed_cached(cv_skill, embed_cache))
            if sim > best_sim:
                best_sim   = sim
                best_proxy = cv_skill

        if best_proxy and best_sim >= PROXY_SIM_THRESHOLD:
            proxy_in_primary = best_proxy in sources.primary
            # Two-hop inference detector: the proxy is in primary tier ONLY
            # because some other declared skill implies it (e.g. Docker → Linux,
            # Spring Boot → Java). It was never literally typed into CV Skills.
            # That's weaker evidence than a literal declaration — discount it
            # so a requirement like "linux command line" doesn't borrow the
            # full 70-point primary base from a skill the candidate never
            # actually wrote down.
            proxy_implied_only = (
                proxy_in_primary
                and best_proxy in sources.implied_primary
                and best_proxy not in sources.declared_literal
            )
            proxy_score1 = (sources.confidence(best_proxy) if proxy_in_primary
                            else sources.experience_only_credit(best_proxy))
            if proxy_implied_only:
                # 35% off — keeps a meaningful signal but caps the
                # double-implication generosity.
                proxy_score1 = round(proxy_score1 * 0.65)

            best_desc_sim = 0.0
            for exp in (cv.work_experience or []):
                if exp.description:
                    desc_vec = _embed_cached(exp.description[:500], embed_cache)
                    sim = _cosine(req_vec, desc_vec)
                    if sim > best_desc_sim:
                        best_desc_sim = sim
            for proj in (cv.projects or []):
                if proj.description:
                    desc_vec = _embed_cached(proj.description[:300], embed_cache)
                    sim = _cosine(req_vec, desc_vec)
                    if sim > best_desc_sim:
                        best_desc_sim = sim

            score2_proxy = _normalize_cosine(best_desc_sim) * 100
            # Blend depends on where the proxy came from:
            #   • Primary-tier proxy (e.g. MySQL declared in CV Skills) is
            #     strong evidence of transferable knowledge — give the declared
            #     skill 40% of the weight, descriptions 60%.
            #   • Experience/project-only proxy is weaker (used but not declared) —
            #     keep the original 10/90 blend so descriptions dominate.
            if proxy_in_primary:
                proxy_final = round(0.40 * proxy_score1 + 0.60 * score2_proxy)
            else:
                proxy_final = round(0.10 * proxy_score1 + 0.90 * score2_proxy)

    # ── Take the best available score and build reason ─────────────────────────
    if direct_final is not None and proxy_final is not None:
        if proxy_final > direct_final:
            final  = proxy_final
            reason = _build_skill_reason(normalized, sources, "PROXY", best_proxy, proxy_in_primary)
        else:
            final  = direct_final
            reason = _build_skill_reason(normalized, sources, "DIRECT")
    elif direct_final is not None:
        final  = direct_final
        reason = _build_skill_reason(normalized, sources, "DIRECT")
    elif proxy_final is not None:
        final  = proxy_final
        reason = _build_skill_reason(normalized, sources, "PROXY", best_proxy, proxy_in_primary)
    else:
        if req_vec:
            emb_sim = _cosine(req_vec, cv_vec)
            final   = round(_normalize_cosine(emb_sim) * 100)
        else:
            final = 0
        reason = _build_skill_reason(normalized, sources, "SEMANTIC")

    return final, _skill_status(final, matched_threshold), reason


def match_job_to_cv(request: SemanticMatchRequest) -> SemanticMatchResult:
    cv            = request.cv_analysis
    embed_cache: dict[str, list[float]] = {}

    # ── Ensure skill_intel is available ───────────────────────────────────────
    # skill_intel (volatility + framework implications) is computed at parse time,
    # but it can be lost in transit if the caller's DTO doesn't carry the field.
    # Recompute it here so /match is self-sufficient — the classifier is cached
    # to a persistent store, so this is just cache hits (near-zero cost).
    if not getattr(cv, "skill_intel", None):
        try:
            from app.skill_intel import get_skill_intelligence
            all_skill_names: list[str] = list(cv.skills or []) + list(cv.knowledge or [])
            for exp in (cv.work_experience or []):
                all_skill_names += list(exp.skills_used or [])
            for proj in (cv.projects or []):
                all_skill_names += list(proj.skills_used or [])
            for hack in (cv.hackathons or []):
                all_skill_names += list(hack.skills_used or [])
            cv.skill_intel = get_skill_intelligence(all_skill_names)
            print(f"[match] Recomputed skill_intel for {len(cv.skill_intel)} skills "
                  f"(was missing from request)")
        except Exception as e:
            print(f"[match] skill_intel recompute skipped: {e}")

    # ── CV text + vector — computed once, reused by skills blend and global sim ─
    cv_text = _build_cv_text(cv)
    cv_vec  = _embed_cached(cv_text, embed_cache)

    # ── Required skills ───────────────────────────────────────────────────────
    required_skill_groups = _extract_required_skill_groups(
        request.requirements, request.job_title, request.job_description
    )
    skill_sources = _build_skill_sources(cv)
    cv_skills     = skill_sources.all_skills

    matched: list[str] = []
    missing: list[str] = []
    skill_scores: list[dict] = []

    def _score_one_skill(skill: str, qualifier: str, threshold: float, prefix: str) -> tuple[int, int, str, dict]:
        """Score one skill, apply the qualifier curve, and build the UI row.
        Returns (raw_score, effective_score, status, row_dict)."""
        raw, _, reason = _compute_skill_score(
            skill, cv, cv_skills, skill_sources, embed_cache, cv_vec, threshold
        )
        effective = _apply_qualifier_curve(raw, qualifier)
        bar       = _QUALIFIER_BARS.get(qualifier, 0)
        signal    = _qualifier_signal(raw, qualifier)
        # Status is judged against the EFFECTIVE score so a candidate who clears
        # the BASIC bar at 50 still counts as matched, while one stuck at 60
        # for an EXPERT requirement drops to ~27 effective and reads as missing.
        status    = _skill_status(effective, threshold)
        evidence  = _extract_source_tags(skill, cv)
        row = {
            "skill":           f"{prefix}{skill}",
            "score":           effective,         # what the UI / aggregation use
            "raw_score":       raw,               # pre-curve, kept for debugging
            "status":          status,
            "qualifier":       qualifier,
            "qualifier_bar":   bar,
            "meets_qualifier": bar <= 0 or raw >= bar,
            "gap_from_qualifier": max(0, bar - raw) if bar > 0 else 0,
            "signal":          signal,
            "evidence":        evidence,
            "reason":          reason,
        }
        return raw, effective, status, row

    for group in required_skill_groups:
        threshold = _QUALIFIER_THRESHOLDS.get(group["qualifier"], SKILL_SEM_THRESHOLD)
        logic     = group["logic"]
        qualifier = group["qualifier"]
        prefix    = f"[{qualifier.upper()}] " if qualifier != "any" else ""

        if logic == "OR":
            # Score every alternative independently and keep the one whose
            # EFFECTIVE score is highest (post-curve). Joining alternatives
            # into "java or javascript" before scoring used to collapse to a
            # weak ~50% semantic match even when the candidate had both.
            best_row:  dict | None = None
            best_eff:  int        = -1
            for skill in group["skills"]:
                _raw, eff, _status, row = _score_one_skill(skill, qualifier, threshold, prefix)
                if eff > best_eff:
                    best_eff = eff
                    best_row = row
            if best_row is not None:
                label = " or ".join(group["skills"])
                best_row["skill"] = f"{prefix}{label}"  # show the OR set, not just the winner
                skill_scores.append(best_row)
                (matched if best_row["status"] == "matched" else missing).append(label)
        else:
            for skill in group["skills"]:
                _raw, _eff, status, row = _score_one_skill(skill, qualifier, threshold, prefix)
                skill_scores.append(row)
                (matched if status == "matched" else missing).append(skill)

    # Skills component: weighted average of effective scores, where each skill
    # is weighted by its qualifier's importance (EXPERT 4× > BASIC 1×). This is
    # what lets "missing one EXPERT skill" matter more than "missing one BASIC
    # skill" in the final job_fit_score.
    if skill_scores:
        weighted_total = 0.0
        weight_total   = 0.0
        for s in skill_scores:
            w = _QUALIFIER_WEIGHTS.get(s.get("qualifier", "any"), 1.0)
            weighted_total += s["score"] * w
            weight_total   += w
        skills_ratio = (weighted_total / weight_total) / 100 if weight_total > 0 else 0.0
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
    job_id_for_cache = getattr(request, "job_id", None)
    # Cache-aware path: pull the job's stored vector from the job-microservice
    # if available, otherwise compute via Ollama and write it back so the next
    # applicant for this job inherits the cached vector. Falls back silently
    # to the in-request cache if anything goes wrong.
    job_vec = _get_or_compute_job_vector(job_id_for_cache, job_text, embed_cache)
    global_sim       = _cosine(job_vec, cv_vec)
    global_sim_score = _normalize_cosine(global_sim)

    # Bulk-fetch every requirement's cached embedding for this job in one call.
    # The matcher consults this map first before falling back to Ollama for any
    # requirement whose vector wasn't cached yet (those get PUT back inside
    # _get_or_compute_req_vector).
    req_vec_prefetched = _prefetch_requirement_vectors(job_id_for_cache)

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
        elif cat in ("LANGUAGE", "LANGUE", "LANGUAGES"):
            score_val, _ = _match_language_req(
                req.description or "", cv, language_level=req.language_level,
            )
            req_score = score_val / 100.0
        elif cat in ("SKILL", "TECHNICAL", "TECHNOLOGY", "COMPÉTENCE"):
            # Score this requirement using the qualifier-aware curve + per-skill
            # qualifier weighting (BASIC 1× → EXPERT 4×). The recruiter's
            # requirement-level `weight` (req.weight) is applied on top, lower
            # in this function, so both knobs compose.
            qualifier_for_req = (req.skill_level or "any").lower() if req.skill_level else None
            groups = _parse_description_to_groups(req.description or "")
            weighted_total   = 0.0
            weight_total     = 0.0
            any_critical_gap = False
            for g in groups:
                qualifier = qualifier_for_req or g["qualifier"]
                thresh    = _QUALIFIER_THRESHOLDS.get(qualifier, SKILL_SEM_THRESHOLD)
                q_w       = _QUALIFIER_WEIGHTS.get(qualifier, 1.0)
                if g["logic"] == "OR":
                    # Score each alternative independently and keep the best
                    # effective score. The joined-string approach was scoring
                    # "java or javascript" as one token and collapsing to weak
                    # semantic similarity (~50%) even when the candidate had
                    # both via framework implications (Spring Boot → Java,
                    # Angular → JavaScript).
                    best_eff = 0
                    best_raw = 0
                    for skill in g["skills"]:
                        raw, _, _r = _compute_skill_score(skill, cv, cv_skills, skill_sources, embed_cache, cv_vec, thresh)
                        eff = _apply_qualifier_curve(raw, qualifier)
                        if eff > best_eff:
                            best_eff = eff
                            best_raw = raw
                    weighted_total += best_eff * q_w
                    weight_total   += q_w
                    if _qualifier_signal(best_raw, qualifier) == "critical_gap":
                        any_critical_gap = True
                else:
                    for skill in g["skills"]:
                        raw, _, _r = _compute_skill_score(skill, cv, cv_skills, skill_sources, embed_cache, cv_vec, thresh)
                        eff = _apply_qualifier_curve(raw, qualifier)
                        weighted_total += eff * q_w
                        weight_total   += q_w
                        if _qualifier_signal(raw, qualifier) == "critical_gap":
                            any_critical_gap = True
            req_score = (weighted_total / weight_total) / 100 if weight_total > 0 else 0.0
        else:
            # EXPERIENCE / other — embedding similarity.
            # Use the cached requirement vector when available; on miss the
            # wrapper computes via Ollama and PUTs the vector back so future
            # applicants reuse it.
            rv = _get_or_compute_req_vector(
                job_id_for_cache,
                getattr(req, "id", None),
                req_text,
                embed_cache,
                req_vec_prefetched,
            )
            req_sim   = _cosine(rv, cv_vec)
            req_score = _normalize_cosine(req_sim)

        req_row: dict = {
            "category":    cat or "REQUIREMENT",
            "description": req.description or "",
            "score":       round(req_score * 100),
            "weight":      weight,
            "evidence":    evidence,
        }
        if cat in ("SKILL", "TECHNICAL", "TECHNOLOGY", "COMPÉTENCE") and req.skill_level:
            req_row["skill_level"]  = req.skill_level
            req_row["critical_gap"] = any_critical_gap
        requirement_scores.append(req_row)
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

    # ── Advisory warnings (non-scoring) ───────────────────────────────────────
    # Distance: ON_SITE job + candidate located far from VERMEG HQ.
    # Name mismatch: CV vs GitHub vs LinkedIn names disagree.
    warnings: list[dict] = []
    distance_w = _check_distance_warning(cv.location, request.work_arrangement)
    if distance_w:
        warnings.append(distance_w)
    warnings.extend(_check_name_warnings(cv))

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
        warnings                = warnings,
    )
