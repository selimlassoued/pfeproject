import re
import json
import os
import time
import logging
import ollama
from typing import Optional, List, Tuple

from app.evaluator import evaluate_cv
from app.extractor import extract_contact_fields, extract_languages_from_text
from app.models import (
    WorkExperience,
    Education,
    Language,
    SocialLinks,
    Hackathon,
    Project,
    VolunteerWork,
    CvAnalysisResult,
)

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

OLLAMA_HOST = os.getenv("OLLAMA_HOST", "http://localhost:11434")
MODEL       = os.getenv("CV_PARSER_MODEL", "qwen2.5:3b")

ollama_client = ollama.Client(host=OLLAMA_HOST)


# ─────────────────────────────────────────────────────────────────────────────
# Seniority — derived from work experience job titles, not full text
# ─────────────────────────────────────────────────────────────────────────────

_SENIOR_TITLE_KEYWORDS = [
    "senior", "lead", "principal", "architect", "manager", "director",
    "ingénieur", "engineer", "researcher", "chercheur",
    "ingénieur de recherche", "research engineer",
]
_INTERN_KEYWORDS = ["intern", "internship", "stage", "stagiaire"]
_JUNIOR_KEYWORDS = ["junior"]
_MID_KEYWORDS    = ["mid", "intermediate"]


def extract_seniority(text: str, job_titles: Optional[List[str]] = None) -> Optional[str]:
    # Ensure all titles are strings
    safe_titles = [t for t in (job_titles or []) if isinstance(t, str)]
    titles_text = " ".join(safe_titles).lower()
    full_lower  = text.lower()

    if titles_text and any(kw in titles_text for kw in _SENIOR_TITLE_KEYWORDS):
        non_intern = [t for t in safe_titles
                      if not any(kw in t.lower() for kw in _INTERN_KEYWORDS)]
        if non_intern:
            return "SENIOR"

    if any(kw in full_lower for kw in _INTERN_KEYWORDS):
        return "INTERN"
    if any(kw in full_lower for kw in _JUNIOR_KEYWORDS):
        return "JUNIOR"
    if any(kw in full_lower for kw in _MID_KEYWORDS):
        return "MID"

    return "INTERN"


# ─────────────────────────────────────────────────────────────────────────────
# Post-processing validators
# ─────────────────────────────────────────────────────────────────────────────

_COMPANY_CITY_PATTERNS = [
    r"\bLac\s+\d",
    r"\bZone\s+Industrielle",
    r"\bCentre\s+Ville\b",
    r"\bZI\b",
]


def _validate_location(location: Optional[str]) -> Optional[str]:
    if not location:
        return None
    for pattern in _COMPANY_CITY_PATTERNS:
        if re.search(pattern, location, re.IGNORECASE):
            return None
    return location


_AVAILABILITY_KEYWORDS = [
    "available", "disponible", "immediately", "immédiatement",
    "from", "à partir", "open to", "seeking", "looking for",
    "starting", "dès",
]
_DURATION_INDICATORS = ["—", "–", " - ", "present", "aujourd", "en cours", "current"]


def _validate_availability(availability: Optional[str]) -> Optional[str]:
    if not availability:
        return None
    av_lower = availability.lower()
    if any(ind in av_lower for ind in _DURATION_INDICATORS):
        return None
    if not any(kw in av_lower for kw in _AVAILABILITY_KEYWORDS):
        return None
    return availability


def _safe(val, is_list: bool = False):
    if val is None or val == "null" or val == "":
        return None
    if is_list:
        if not isinstance(val, list):
            return []
        result = []
        for v in val:
            if not v or v == "null":
                continue
            if isinstance(v, str):
                result.append(v)
            elif isinstance(v, dict):
                str_val = (v.get("name") or v.get("title") or v.get("skill")
                           or v.get("value") or v.get("text") or "")
                if str_val:
                    result.append(str(str_val))
            elif isinstance(v, (int, float)):
                result.append(str(v))
        return result
    # Scalar: always return a string or None
    if isinstance(val, dict):
        # LLM returned a dict where a string was expected — extract best string value
        str_val = (val.get("name") or val.get("title") or val.get("value")
                   or val.get("text") or val.get("description") or "")
        return str(str_val) if str_val else None
    if isinstance(val, (int, float)):
        return str(val)
    if isinstance(val, str):
        return val if val.strip() else None
    return None


# ─────────────────────────────────────────────────────────────────────────────
# Shared model caller with full logging + retry
# ─────────────────────────────────────────────────────────────────────────────

def _call_model(prompt_name: str, prompt: str, retries: int = 2) -> dict:
    """
    Call the LLM with full logging so you can see exactly what's happening.
    Logs: start time, response time, raw response length, parse errors.
    """
    raw = ""
    for attempt in range(retries):
        t0 = time.time()
        logger.info(f"[{prompt_name}] Starting (attempt {attempt + 1}/{retries}) model={MODEL}")
        try:
            response = ollama_client.chat(
                model=MODEL,
                messages=[{"role": "user", "content": prompt}],
                options={"temperature": 0, "num_predict": 2048},
            )
            elapsed = time.time() - t0
            raw = response["message"]["content"].strip()

            # Strip thinking blocks (Qwen3 series)
            raw = re.sub(r"<think>.*?</think>", "", raw, flags=re.DOTALL).strip()
            raw = re.sub(r"```json\s*|```\s*", "", raw).strip()

            logger.info(f"[{prompt_name}] Response in {elapsed:.1f}s, {len(raw)} chars")

            result = json.loads(raw)
            logger.info(f"[{prompt_name}] ✓ Parsed OK, keys={list(result.keys())}")
            return result

        except json.JSONDecodeError as e:
            elapsed = time.time() - t0
            logger.error(f"[{prompt_name}] ✗ JSON error after {elapsed:.1f}s: {e}")
            logger.error(f"[{prompt_name}] Raw (first 300 chars): {raw[:300]!r}")
            m = re.search(r"\{.*\}", raw, re.DOTALL)
            if m:
                try:
                    result = json.loads(m.group(0))
                    logger.info(f"[{prompt_name}] ✓ Recovered JSON from response")
                    return result
                except Exception:
                    pass
            if attempt == retries - 1:
                logger.error(f"[{prompt_name}] All retries exhausted → returning {{}}")
                return {}

        except AttributeError as e:
            # Model returned a JSON array instead of object — wrap it
            elapsed = time.time() - t0
            logger.warning(f"[{prompt_name}] Model returned array instead of object, wrapping")
            try:
                arr = json.loads(raw)
                if isinstance(arr, list):
                    # Guess the key from the prompt name
                    key_map = {
                        "EXPERIENCE": "work_experience",
                        "EDUCATION": "education",
                        "SKILLS": "skills",
                        "ACTIVITIES": "projects",
                    }
                    key = key_map.get(prompt_name, "data")
                    return {key: arr}
            except Exception:
                pass
            if attempt == retries - 1:
                return {}

        except Exception as e:
            elapsed = time.time() - t0
            logger.error(f"[{prompt_name}] ✗ Error after {elapsed:.1f}s: {type(e).__name__}: {e}")
            if attempt == retries - 1:
                logger.error(f"[{prompt_name}] All retries exhausted → returning {{}}")
                return {}

    return {}


# ─────────────────────────────────────────────────────────────────────────────
# Prompt 1 — Identity (5 fields)
# ─────────────────────────────────────────────────────────────────────────────

def _extract_identity(text: str) -> dict:
    prompt = f"""You are a CV parser. Extract ONLY these 5 fields from the CV. Return ONLY valid JSON, no explanation.

{{
  "candidate_name": "full name or null",
  "location": "candidate home address or home city only — null if only company/school cities appear",
  "summary": "1-2 sentence profile/objective summary or null",
  "desired_position": "job title the candidate is seeking or null",
  "availability": "when candidate can start — only if explicitly stated, null otherwise"
}}

Important:
- candidate_name: usually the largest text at the top of the CV, or next to a name label.
- location: extract ONLY if explicitly labeled as home address (Address:, Adresse:, etc.). Never use workplace or university city.
- summary: look for a PROFILE, PROFIL, ABOUT, OBJECTIVE, or SUMMARY section. Extract the paragraph describing the candidate. This is different from work descriptions.
- desired_position: the job title the candidate is applying for, or the title shown at the top of the CV.
- availability: only explicit statements like "available immediately", "disponible dès septembre 2025". A job date range is NOT availability.
- If a field is absent, return null — never invent values.

CV TEXT:
{text[:3000]}"""
    return _call_model("IDENTITY", prompt)


# ─────────────────────────────────────────────────────────────────────────────
# Prompt 2 — Work Experience
# ─────────────────────────────────────────────────────────────────────────────

def _extract_experience(text: str) -> dict:
    prompt = f"""You are a CV parser. Extract ALL work experience entries. Return ONLY a valid JSON object, no explanation.

{{
  "work_experience": [
    {{
      "title": "job title only",
      "company": "employer name or null",
      "duration": "date range or null",
      "description": "responsibilities/achievements or null",
      "skills_used": ["technology or skill used in this role"]
    }}
  ],
  "total_years_experience": 0
}}

Important:
- Extract EVERY professional role — internships, full-time jobs, freelance, research positions.
- title must contain ONLY the role name, never the company name.
- When role and company appear on the same line separated by dash or comma, split them:
  "Software Engineer - Google" → title="Software Engineer", company="Google"
  "Intern, Acme Corp" → title="Intern", company="Acme Corp"
  "DATE | Role | Company | City" → title="Role", company="Company"
- NEVER include academic degrees or diplomas as work experience.
- total_years_experience: sum of all durations. Return 0 for students/recent graduates.
- Return [] if no work experience exists.

CV TEXT:
{text[:5000]}"""
    return _call_model("EXPERIENCE", prompt)


# ─────────────────────────────────────────────────────────────────────────────
# Prompt 3 — Education
# ─────────────────────────────────────────────────────────────────────────────

def _extract_education(text: str) -> dict:
    prompt = f"""You are a CV parser. Extract ALL education entries. Return ONLY valid JSON, no explanation.

{{
  "education": [
    {{
      "degree": "diploma or degree title",
      "institution": "school or university name only",
      "year": "graduation year or date range",
      "field": "field of study or null",
      "mention": "honors, GPA, ranking, or distinction — null if absent"
    }}
  ]
}}

Important:
- Extract EVERY education entry — high school, bachelor, master, PhD, etc.
- institution must contain ONLY the school name. Strip any honors suffix:
  "MIT - Summa Cum Laude" → institution="MIT", mention="Summa Cum Laude"
- Some entries appear on a SINGLE LINE. Parse all fields from it:
  "BSc Computer Science (with Honours) 2020 University of London" → degree="BSc Computer Science", mention="with Honours", year="2020", institution="University of London"
- Strip qualifiers like "(ongoing)", "(in progress)" from degree title and put in mention if relevant.
- NEVER include jobs, internships, or certifications here.
- Return [] if no education found.

CV TEXT:
{text[:5000]}"""
    return _call_model("EDUCATION", prompt)


# ─────────────────────────────────────────────────────────────────────────────
# Prompt 4 — Skills
# ─────────────────────────────────────────────────────────────────────────────

def _extract_skills(text: str) -> dict:
    prompt = f"""You are a CV parser. Extract skills, soft skills, and certifications. Return ONLY valid JSON, no explanation.

{{
  "skills": ["skill1", "skill2"],
  "soft_skills": ["trait1"],
  "certifications": ["cert1"]
}}

Important:
- skills: extract ALL technical skills, tools, languages, frameworks, and methodologies from the dedicated skills section. Also extract from sub-categories like "Databases: MySQL, PostgreSQL" → extract "MySQL", "PostgreSQL".
- soft_skills: behavioral and interpersonal traits ONLY. Extract from the profile/summary paragraph. Never invent defaults — only include what is explicitly written.
- certifications: professional certificates explicitly labeled as such (AWS Certified, CCNA, etc.). NEVER include academic degrees.
- The CV may use French section names: COMPÉTENCES = skills, CERTIFICATIONS = certifications.
- Return [] for any absent section.

CV TEXT:
{text[:5000]}"""
    return _call_model("SKILLS", prompt)


# ─────────────────────────────────────────────────────────────────────────────
# Prompt 5 — Activities
# ─────────────────────────────────────────────────────────────────────────────

def _extract_activities(text: str) -> dict:
    prompt = f"""You are a CV parser. Extract projects, hackathons, volunteer work, awards, and social links. Return ONLY valid JSON, no explanation.

{{
  "projects": [{{"title": "project name", "description": "what was built or null", "skills_used": [], "url": "link or null"}}],
  "hackathons": [{{"title": "competition name", "rank": "placement or null", "date": "month year or null", "description": "what was built/achieved or null", "skills_used": []}}],
  "volunteer_work": [{{"role": "role title only", "organization": "org name or null", "duration": "date range or null", "description": "activities or null"}}],
  "awards": ["award or prize name"],
  "social_links": {{"linkedin": "full URL or null", "github": "full URL or null", "portfolio": "full URL or null"}}
}}

Important:
- projects: personal or academic projects ONLY. Internships and jobs are NOT projects.
- hackathons: look in EXTRACURRICULAR ACTIVITIES, ACTIVITÉS, and throughout the CV for competition results.
  Competitions often appear as: "DATE | Nth Place, Competition Name" or "Nth Place, Competition Name DATE"
  Extract ALL competition entries — there may be multiple.
  Always extract the paragraph that follows as the description.
  Rank examples: "1st Place", "2nd Place", "Winner", "1er Prix", "Finalist"
- volunteer_work: look in EXTRACURRICULAR ACTIVITIES, EXPÉRIENCE ASSOCIATIVE sections.
  Extract associative roles, student clubs, community work, leadership positions.
  ONE entry per role. The description is the paragraph explaining the role activities.
  If role and organization appear together like "Treasurer, IEEE Club" → role="Treasurer", organization="IEEE Club"
- awards: competition prizes, academic honors ONLY. Not degrees or job titles.
  For ranked competitions use "Nth Place, Competition Name" format.
  Do NOT add both "1st Place, Name" AND "Name" — pick only the ranked version.
- social_links: reconstruct full URLs. LinkedIn must contain /in/username to be valid.
- Return [] for absent sections.

CV TEXT:
{text[:5000]}"""
    return _call_model("ACTIVITIES", prompt)


# ─────────────────────────────────────────────────────────────────────────────
# Other post-processing helpers
# ─────────────────────────────────────────────────────────────────────────────

def _flush_model_context() -> None:
    """
    Flush Ollama's KV cache between CV parses to prevent context contamination
    from previous requests bleeding into the current parse.
    """
    try:
        ollama_client.generate(model=MODEL, prompt="", keep_alive=0)
    except Exception:
        pass  # Best-effort — don't fail the parse if flush fails


def _text_contains_hackathons(text: str) -> bool:
    """Check if the CV text actually mentions hackathon/competition events."""
    keywords = [
        "hackathon", "competition", "place", "1st", "2nd", "3rd",
        "concours", "compétition", "classement", "prix"
    ]
    text_lower = text.lower()
    return any(kw in text_lower for kw in keywords)


def _text_contains_volunteer(text: str) -> bool:
    """Check if the CV text actually mentions volunteer/associative activities."""
    keywords = [
        "volunteer", "bénévol", "associat", "ieee", "club", "student branch",
        "chairwoman", "chairman", "treasurer", "president", "ambassador",
        "extracurricular", "activités", "expérience associative"
    ]
    text_lower = text.lower()
    return any(kw in text_lower for kw in keywords)


def _split_title_company(title: Optional[str]) -> Tuple[Optional[str], Optional[str]]:
    """Split 'ROLE - COMPANY' or 'ROLE, COMPANY' into separate fields."""
    if not title:
        return title, None
    m = re.match(
        r"^(.+?)\s+-\s+([A-ZÉÈÀÂÊÎÔÛÇÙŒÆa-zà-ÿ][A-ZÉÈÀÂÊÎÔÛÇÙŒÆa-zà-ÿ0-9\s]+)$",
        title.strip(),
    )
    if m and len(m.group(2).strip().split()) <= 3:
        return m.group(1).strip(), m.group(2).strip()
    m = re.match(
        r"^([^,]+),\s+([A-ZÉÈÀÂÊÎÔÛÇÙŒÆa-zà-ÿ][A-ZÉÈÀÂÊÎÔÛÇÙŒÆa-zà-ÿ0-9\s]+)$",
        title.strip(),
    )
    if m and len(m.group(2).strip().split()) <= 4:
        return m.group(1).strip(), m.group(2).strip()
    return title, None


def _extract_rank_from_title(title: str) -> Tuple[str, Optional[str]]:
    """
    If hackathon title starts with rank like '1st Place, AI Hackathon',
    extract the rank and return clean title separately.
    """
    if not title:
        return title, None
    m = re.match(r"^(\d+(?:st|nd|rd|th)\s+[Pp]lace)[,\s]+(.+)$", title.strip())
    if m:
        return m.group(2).strip(), m.group(1).strip()
    m = re.match(r"^(First|Second|Third)\s+[Pp]lace[,\s]+(.+)$", title.strip(), re.IGNORECASE)
    if m:
        rank_map = {"first": "1st Place", "second": "2nd Place", "third": "3rd Place"}
        rank = rank_map.get(m.group(1).lower(), m.group(1) + " Place")
        return m.group(2).strip(), rank
    return title, None


def _clean_volunteer_role(role: str, org: Optional[str]) -> Tuple[str, Optional[str]]:
    """Remove organization name embedded in role field after a comma."""
    if not role or "," not in role:
        return role, org
    parts = role.split(",", 1)
    clean_role = parts[0].strip()
    embedded_org = parts[1].strip()
    return clean_role, org if org else embedded_org


def _fix_hackathon_date_field(hackathons: List[Hackathon]) -> List[Hackathon]:
    for hack in hackathons:
        if hack.date and len(hack.date.split()) > 4:
            if not hack.description:
                hack.description = hack.date
            hack.date = None
    return hackathons


def _derive_awards_from_hackathons(hackathons: List[Hackathon], awards: List[str]) -> List[str]:
    # Normalize awards — LLM sometimes returns dicts instead of strings
    normalized = []
    for a in awards:
        if isinstance(a, str):
            normalized.append(a)
        elif isinstance(a, dict):
            # Extract string from common dict shapes: {"title": "..."}, {"award": "..."}, {"name": "..."}
            val = a.get("title") or a.get("award") or a.get("name") or a.get("description") or ""
            if val:
                normalized.append(str(val))
    awards = normalized

    awards_lower = {a.lower() for a in awards}
    for hack in hackathons:
        if not hack.rank or not hack.title:
            continue
        title_lower = hack.title.lower()
        if any(title_lower in existing for existing in awards_lower):
            continue
        awards.append(hack.title)
        awards_lower.add(title_lower)
    return awards


def _extract_skills_from_text(text: str) -> List[str]:
    """
    Deterministically extract skills from the SKILLS/COMPÉTENCES section.
    Skips language names, level keywords, and prose sentences.
    """
    from app.extractor import _LANGUAGE_NAMES

    # Build a comprehensive set of words to skip
    lang_names_lower = {k.lower() for k in _LANGUAGE_NAMES}
    # Add canonical English language names that might not be in the mapping keys
    lang_names_lower.update({
        "arabic", "french", "english", "spanish", "german", "italian",
        "chinese", "japanese", "russian", "portuguese", "dutch", "turkish",
        "korean", "arabe", "francais", "anglais", "espagnol",
    })

    skill_start = re.search(
        r"(?:TECHNICAL\s+SKILLS?|SKILLS?|COMPÉTENCES?)\s*\n",
        text, re.IGNORECASE
    )
    if not skill_start:
        return []

    next_sec = re.search(
        r"\n(?:PROFESSIONAL|EXPERIENCE|EDUCATION|FORMATION|PROFILE|PROFIL|"
        r"EXTRACURRICULAR|VOLUNTEER|LANGUES?|LANGUAGES?|CERTIF|AWARDS?|PROJECTS?)\b",
        text[skill_start.end():], re.IGNORECASE
    )
    max_end = skill_start.end() + 300
    section_end = skill_start.end() + next_sec.start() if next_sec else max_end
    section_end = min(section_end, max_end)

    skill_text = text[skill_start.end(): section_end]

    skills = []
    seen = set()
    level_words = {
        "native", "advanced", "beginner", "professional", "fluent",
        "intermediate", "biginner", "niveau", "maternelle", "courant",
        "language", "proficiency", "speaker",
    }

    for line in skill_text.split("\n"):
        line = line.strip()
        if not line:
            continue
        if len(line.split()) > 6:
            continue
        if line.isupper() and len(line) > 15:
            continue

        tokens = re.split(r"[,|•\t]|\s{2,}", line)
        for token in tokens:
            token = token.strip()
            if not token or len(token) < 2:
                continue
            if token.lower() in lang_names_lower:
                continue
            if token.lower() in level_words:
                continue
            if re.match(r"^\d", token):
                continue
            if token.lower() not in seen:
                seen.add(token.lower())
                skills.append(token)

    return skills


def _calculate_years_experience(experience_list: List[WorkExperience]) -> Optional[float]:
    """Calculate total years of experience deterministically from duration fields."""
    from datetime import date as dt_date
    MONTHS_MAP = {
        "jan":1,"feb":2,"mar":3,"apr":4,"may":5,"jun":6,
        "jul":7,"aug":8,"sep":9,"oct":10,"nov":11,"dec":12,
        "janvier":1,"février":2,"mars":3,"avril":4,"mai":5,"juin":6,
        "juillet":7,"août":8,"septembre":9,"octobre":10,"novembre":11,"décembre":12,
    }

    def _parse(s):
        s = s.strip().lower()
        if any(w in s for w in ("present","today","now","cours","aujourd")):
            t = dt_date.today()
            return dt_date(t.year, t.month, 1)
        m = re.search(r"([a-zéûôîà]+)\s+(\d{4})", s)
        if m:
            mon = MONTHS_MAP.get(m.group(1)[:4])
            if mon:
                return dt_date(int(m.group(2)), mon, 1)
        m = re.search(r"(\d{4})", s)
        if m:
            return dt_date(int(m.group(1)), 1, 1)
        return None

    total_months = 0
    for exp in experience_list:
        if not exp.duration:
            continue
        dur = exp.duration.replace("—", "-").replace("–", "-")
        parts = [p.strip() for p in dur.split("-") if p.strip()]
        if len(parts) < 2:
            continue
        start = _parse(parts[0])
        end   = _parse(parts[1])
        if start and end and end > start:
            total_months += (end.year - start.year) * 12 + (end.month - start.month)

    if total_months == 0:
        return None
    return round(total_months / 12, 1)


# ─────────────────────────────────────────────────────────────────────────────
# Main parse entry point
# ─────────────────────────────────────────────────────────────────────────────

def parse_cv(text: str, application_id: str) -> CvAnalysisResult:
    try:
        t_start = time.time()
        logger.info(f"=== CV parse start: {application_id}, text={len(text)} chars, model={MODEL} ===")

        # Flush Ollama context to prevent previous CV data bleeding into this parse
        _flush_model_context()

        # ── Deterministic (instant) ───────────────────────────────────────────
        contact   = extract_contact_fields(text)
        languages = extract_languages_from_text(text)
        logger.info(f"Deterministic: email={contact['email']}, phone={contact['phone']}, langs={len(languages)}")

        # ── 5 sequential LLM calls ────────────────────────────────────────────
        # Note: local Ollama processes one request at a time regardless,
        # so parallel calls just create a queue — sequential is cleaner and more stable
        logger.info("Starting 5 sequential LLM calls...")
        t_llm = time.time()

        identity_data   = _extract_identity(text)
        experience_data = _extract_experience(text)
        education_data  = _extract_education(text)
        skills_data     = _extract_skills(text)
        activities_data = _extract_activities(text)

        logger.info(f"All LLM calls finished in {time.time() - t_llm:.1f}s total")

        # ── Education ─────────────────────────────────────────────────────────
        education_list = []
        for edu in education_data.get("education", []):
            education_list.append(Education(
                degree=_safe(edu.get("degree")),
                institution=_safe(edu.get("institution")),
                year=_safe(str(edu.get("year"))) if edu.get("year") else None,
                field=_safe(edu.get("field")),
                mention=_safe(edu.get("mention")),
            ))

        # ── Work experience ───────────────────────────────────────────────────
        experience_list = []
        job_titles = []
        experience_descriptions = set()  # track to filter fake projects later
        for exp in experience_data.get("work_experience", []):
            raw_title   = _safe(exp.get("title"))
            raw_company = _safe(exp.get("company"))
            # Always attempt to split title/company — even if company already set,
            # the title might still contain the company name
            if raw_title:
                split_title, split_company = _split_title_company(raw_title)
                # Only use split result if it actually changed the title
                if split_title != raw_title:
                    raw_title = split_title
                    if not raw_company:
                        raw_company = split_company
            if raw_title:
                job_titles.append(raw_title)
            desc = _safe(exp.get("description"))
            if desc:
                experience_descriptions.add(desc[:80].lower())
            experience_list.append(WorkExperience(
                title=raw_title,
                company=raw_company,
                duration=_safe(exp.get("duration")),
                description=desc,
                skills_used=_safe(exp.get("skills_used", []), is_list=True) or [],
            ))

        # ── Total years experience — calculated deterministically ─────────────
        total_years = _calculate_years_experience(experience_list)

        # ── Seniority ─────────────────────────────────────────────────────────
        seniority = extract_seniority(text, job_titles)

        # ── Languages — always deterministic, LLM never used ─────────────────
        languages_list = [
            Language(name=lang["name"], level=lang["level"])
            for lang in languages
        ]

        # ── Projects — filter copies of internship/job descriptions and education ──
        education_degrees = {
            (edu.degree or "").lower()[:60]
            for edu in education_list if edu.degree
        }
        experience_titles_lower = {
            (exp.title or "").lower()
            for exp in experience_list if exp.title
        }
        projects_list = []
        for proj in activities_data.get("projects", []):
            title = proj.get("title", "")
            desc  = _safe(proj.get("description"))
            title_lower = title.lower()[:80]

            # Skip if title matches a work experience title exactly
            is_exp_title = any(
                exp_t in title_lower or title_lower in exp_t
                for exp_t in experience_titles_lower
            )
            # Skip if title matches work experience description
            is_work_dup = any(
                exp_desc in title_lower or title_lower in exp_desc
                for exp_desc in experience_descriptions
            )
            # Skip if title matches education degree
            is_edu_dup = any(
                edu_deg in title_lower or title_lower in edu_deg
                for edu_deg in education_degrees
            )
            # Skip obvious job task phrases
            is_job_task = any(kw in title_lower for kw in [
                "internship", "stage", "stagiaire", "intern",
            ])
            if is_exp_title or is_work_dup or is_edu_dup or is_job_task:
                logger.info(f"Filtered fake project: {title[:60]}")
                continue
            projects_list.append(Project(
                title=title,
                description=desc,
                skills_used=_safe(proj.get("skills_used", []), is_list=True) or [],
                url=_safe(proj.get("url")),
            ))

        # ── Hackathons — only keep if CV actually mentions competitions ────────
        hackathons_list = []
        if _text_contains_hackathons(text):
            for hack in activities_data.get("hackathons", []):
                raw_title = hack.get("title", "")
                raw_rank  = _safe(hack.get("rank"))
                clean_title, extracted_rank = _extract_rank_from_title(raw_title)
                final_rank = raw_rank or extracted_rank
                hackathons_list.append(Hackathon(
                    title=clean_title,
                    rank=final_rank,
                    date=_safe(hack.get("date")),
                    description=_safe(hack.get("description")),
                    skills_used=_safe(hack.get("skills_used", []), is_list=True) or [],
                ))
            hackathons_list = _fix_hackathon_date_field(hackathons_list)
        else:
            logger.info("No hackathon keywords in CV text — skipping hackathon extraction")

        # ── Volunteer work — only keep if CV actually mentions associations ────
        volunteer_list = []
        if _text_contains_volunteer(text):
            for vol in activities_data.get("volunteer_work", []):
                role = _safe(vol.get("role"))
                if not role:
                    continue
                org  = _safe(vol.get("organization"))
                role, org = _clean_volunteer_role(role, org)
                volunteer_list.append(VolunteerWork(
                    role=role,
                    organization=org,
                    duration=_safe(vol.get("duration")),
                    description=_safe(vol.get("description")),
                ))
        else:
            logger.info("No volunteer keywords in CV text — skipping volunteer extraction")

        # ── Awards ────────────────────────────────────────────────────────────
        awards = _safe(activities_data.get("awards", []), is_list=True) or []
        awards = _derive_awards_from_hackathons(hackathons_list, awards)

        # ── Social links — regex always overrides LLM ─────────────────────────
        social_raw = activities_data.get("social_links", {}) or {}

        def _prefer(contact_val, llm_val):
            return contact_val or _safe(llm_val)

        linkedin = _prefer(contact["linkedin"], social_raw.get("linkedin"))
        if linkedin and "/in/" not in linkedin:
            linkedin = None

        social_links = SocialLinks(
            linkedin=linkedin,
            github=_prefer(contact["github"], social_raw.get("github")),
            portfolio=_safe(social_raw.get("portfolio")),
        )

        # ── Validated identity fields ─────────────────────────────────────────
        location     = _validate_location(_safe(identity_data.get("location")))
        availability = _validate_availability(_safe(identity_data.get("availability")))

        # ── Skills — merge LLM skills with deterministic extraction ──────────
        llm_skills = _safe(skills_data.get("skills", []), is_list=True) or []
        # Also extract skills deterministically from raw text
        det_skills = _extract_skills_from_text(text)
        # Merge: prefer LLM order, add deterministic skills not already present
        merged_skills = list(llm_skills)
        llm_lower = {s.lower() for s in merged_skills if isinstance(s, str)}
        for s in det_skills:
            if isinstance(s, str) and s.lower() not in llm_lower:
                merged_skills.append(s)
                llm_lower.add(s.lower())

        result = CvAnalysisResult(
            application_id=application_id,
            candidate_name=_safe(identity_data.get("candidate_name")),
            email=contact["email"] or _safe(identity_data.get("email")),
            phone=contact["phone"] or _safe(identity_data.get("phone")),
            location=location,
            social_links=social_links,
            summary=_safe(identity_data.get("summary")),
            desired_position=_safe(identity_data.get("desired_position")),
            availability=availability,
            skills=merged_skills,
            soft_skills=_safe(skills_data.get("soft_skills", []), is_list=True) or [],
            languages=languages_list,
            certifications=_safe(skills_data.get("certifications", []), is_list=True) or [],
            work_experience=experience_list,
            total_years_experience=total_years,
            seniority_level=seniority,
            education=education_list,
            projects=projects_list,
            hackathons=hackathons_list,
            volunteer_work=volunteer_list,
            awards=awards,
            raw_text_length=len(text),
            parsing_status="SUCCESS",
        )

        result.evaluation = evaluate_cv(result, text)
        logger.info(f"=== CV parse DONE in {time.time() - t_start:.1f}s total ===")
        return result

    except Exception as e:
        logger.error(f"CV parse FAILED: {type(e).__name__}: {e}")
        return CvAnalysisResult(
            application_id=application_id,
            parsing_status="FAILED",
            error_message=str(e),
        )