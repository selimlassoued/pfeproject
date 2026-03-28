import re
import json
import os
from datetime import date
from typing import Optional, Tuple, List

import ollama

from app.models import CvAnalysisResult, CvEvaluation, EvidenceSignals

OLLAMA_HOST = os.getenv("OLLAMA_HOST", "http://localhost:11434")
ollama_client = ollama.Client(host=OLLAMA_HOST)

MONTHS = {
    "jan": 1, "january": 1, "janvier": 1,
    "feb": 2, "february": 2, "février": 2, "fevrier": 2,
    "mar": 3, "march": 3, "mars": 3,
    "apr": 4, "april": 4, "avr": 4, "avril": 4,
    "may": 5, "mai": 5,
    "jun": 6, "june": 6, "juin": 6,
    "jul": 7, "july": 7, "juil": 7, "juillet": 7,
    "aug": 8, "august": 8, "août": 8, "aout": 8,
    "sep": 9, "sept": 9, "september": 9, "septembre": 9,
    "oct": 10, "october": 10, "octobre": 10,
    "nov": 11, "november": 11, "novembre": 11,
    "dec": 12, "december": 12, "déc": 12, "decembre": 12, "décembre": 12,
}

TECH_STACK = {
    "Angular", "React", "Vue", "Spring Boot", "Node", "Python", "Java",
    "Flutter", "Docker", "Kubernetes", "MySQL", "PostgreSQL", "MongoDB",
    "TypeScript", "JavaScript", "C#", ".NET", "FastAPI", "Django",
}


def _append_unique(target: List[str], value: str) -> None:
    if value and value not in target:
        target.append(value)


def parse_month_year(part: str) -> Optional[date]:
    if not part:
        return None
    part = part.strip().lower()
    if part in {"present", "current", "now", "today", "en cours", "aujourd'hui", "aujourd'hui"}:
        t = date.today()
        return date(t.year, t.month, 1)
    m = re.search(r"([A-Za-zÀ-ÿ]+)\s+(\d{4})", part)
    if m:
        month = MONTHS.get(m.group(1).lower())
        if month:
            return date(int(m.group(2)), month, 1)
    m = re.search(r"(\d{4})", part)
    if m:
        return date(int(m.group(1)), 1, 1)
    return None


def parse_duration_range(duration: Optional[str]) -> Tuple[Optional[date], Optional[date]]:
    if not duration:
        return None, None
    text = duration.strip().lower().replace("–", "-").replace("—", "-")
    m = re.search(r"(\d{2})/(\d{2})/(\d{4}).*?(\d{2})/(\d{2})/(\d{4})", text)
    if m:
        return (
            date(int(m.group(3)), int(m.group(2)), int(m.group(1))),
            date(int(m.group(6)), int(m.group(5)), int(m.group(4))),
        )
    m = re.search(r"(\d{4})\s*-\s*(\d{4})", text)
    if m:
        return date(int(m.group(1)), 1, 1), date(int(m.group(2)), 1, 1)
    parts = [p.strip() for p in text.split("-") if p.strip()]
    if len(parts) >= 2:
        return parse_month_year(parts[0]), parse_month_year(parts[1])
    return parse_month_year(text), None


def months_between(d1: date, d2: date) -> int:
    return (d2.year - d1.year) * 12 + (d2.month - d1.month)


def is_student_profile(cv: CvAnalysisResult) -> bool:
    if cv.seniority_level == "INTERN":
        return True
    for edu in cv.education:
        if edu.year and any(w in edu.year.lower()
                            for w in ("present", "cours", "current", "aujourd")):
            return True
    return False


def detect_missing_sections(cv: CvAnalysisResult, ev: CvEvaluation) -> None:
    if not cv.summary:           ev.missing_sections.append("Summary")
    if not cv.skills:            ev.missing_sections.append("Skills")
    if not cv.education:         ev.missing_sections.append("Education")
    if not cv.work_experience:   ev.missing_sections.append("Work Experience")
    if not cv.projects:          ev.missing_sections.append("Projects")
    if not cv.languages:         ev.missing_sections.append("Languages")


def detect_completeness(cv: CvAnalysisResult, ev: CvEvaluation) -> None:
    ev.has_email      = bool(cv.email)
    ev.has_phone      = bool(cv.phone)
    ev.has_linkedin   = bool(cv.social_links and cv.social_links.linkedin)
    ev.has_github     = bool(cv.social_links and cv.social_links.github)
    ev.has_portfolio  = bool(cv.social_links and cv.social_links.portfolio)
    ev.has_projects   = len(cv.projects) > 0
    ev.has_experience = len(cv.work_experience) > 0
    ev.has_education  = len(cv.education) > 0
    ev.has_skills     = len(cv.skills) > 0
    ev.has_languages  = len(cv.languages) > 0

    if not ev.has_email:     _append_unique(ev.profile_weaknesses, "Missing email")
    if not ev.has_phone:     _append_unique(ev.profile_weaknesses, "Missing phone number")
    if not ev.has_linkedin:  _append_unique(ev.profile_weaknesses, "No LinkedIn profile provided")
    if not ev.has_github:    _append_unique(ev.profile_weaknesses, "No GitHub profile provided")
    if not ev.has_projects:  _append_unique(ev.profile_weaknesses, "No standalone projects listed")

    if ev.has_skills:     _append_unique(ev.profile_strengths, "Technical skills section detected")
    if ev.has_education:  _append_unique(ev.profile_strengths, "Education history detected")
    if ev.has_experience: _append_unique(ev.profile_strengths, "Work experience detected")
    if ev.has_languages:  _append_unique(ev.profile_strengths, "Languages section detected")


def detect_structure_warnings(cv: CvAnalysisResult, ev: CvEvaluation) -> None:
    for exp in cv.work_experience:
        missing = [f for f, v in [("title", exp.title), ("company", exp.company),
                                   ("duration", exp.duration), ("description", exp.description)]
                   if not v]
        if missing:
            ev.incomplete_experience_entries_count += 1
            ev.structure_warnings.append(
                f"Work experience entry is incomplete: missing {', '.join(missing)}"
            )
        if exp.description and len(exp.description.split()) < 8:
            ev.structure_warnings.append(
                f"Work experience '{exp.title or 'Untitled'}' description is very short"
            )
    for edu in cv.education:
        missing = [f for f, v in [("degree", edu.degree), ("institution", edu.institution),
                                   ("year", edu.year)] if not v]
        if missing:
            ev.incomplete_education_entries_count += 1
            ev.structure_warnings.append(
                f"Education entry is incomplete: missing {', '.join(missing)}"
            )


def detect_date_warnings_and_gaps(cv: CvAnalysisResult, ev: CvEvaluation) -> None:
    parsed_ranges: List[Tuple[date, date, str]] = []
    student = is_student_profile(cv)

    for exp in cv.work_experience:
        start, end = parse_duration_range(exp.duration)
        label = exp.title or "Untitled"
        if exp.duration and not start:
            ev.date_warnings.append(f"Could not parse start date for experience '{label}'")
        if exp.duration and "present" not in (exp.duration or "").lower() \
                and "aujourd" not in (exp.duration or "").lower() and not end:
            ev.date_warnings.append(f"Could not parse end date for experience '{label}'")
        if start and end and start > end:
            ev.date_warnings.append(
                f"Invalid date range in experience '{label}': start is after end"
            )
        if start:
            parsed_ranges.append((start, end or date.today(), label))

    parsed_ranges.sort(key=lambda x: x[0])
    for i in range(len(parsed_ranges) - 1):
        _, cur_end, cur_title = parsed_ranges[i]
        nxt_start, _, nxt_title = parsed_ranges[i + 1]
        gap = months_between(cur_end, nxt_start)
        if gap > 4:
            ev.experience_gap_count += 1
            msg = (f"Gap of {gap} months between '{cur_title}' and '{nxt_title}'"
                   + (" (may be due to ongoing studies)" if student else ""))
            ev.gap_warnings.append(msg)
        if months_between(nxt_start, cur_end) > 0:
            ev.date_warnings.append(
                f"Possible overlap between '{cur_title}' and '{nxt_title}'"
            )


def _strip_contact_data(text: str) -> str:
    text = re.sub(r"[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,6}", " ", text)
    text = re.sub(r"https?://\S+", " ", text)
    text = re.sub(r"\+?\d[\d\s\-\(\)]{7,}\d", " ", text)
    return text


def _regex_spell_check(text: str) -> list:
    """
    Fast deterministic spell check for common CV typo patterns.
    Catches misspellings that Mistral frequently misses or hallucinates.
    Returns list of {"word": str, "suggestion": str}.
    """
    KNOWN_TYPOS = {
        # CV title typos
        "developper": "developer", "developpeur": "développeur",
        # Common profile/description typos
        "architctures": "architectures", "architecure": "architecture",
        "systms": "systems", "sytem": "system",
        "numrous": "numerous", "professionnal": "professional",
        "experiense": "experience", "expérience": "expérience",
        "managment": "management", "devlopment": "development",
        "knowlegde": "knowledge", "recrutment": "recruitment",
        "recrutement": "recrutement",
        # Language level typos
        "biginner": "beginner", "begginer": "beginner",
        "intermediaire": "intermédiaire",
        # Role typos
        "treausrer": "treasurer", "tresurer": "treasurer",
        # Other
        "colaborer": "collaborer", "colaborate": "collaborate",
        "compétencs": "compétences",
    }

    found = []
    seen = set()
    # Search word by word in the text
    words = re.findall(r"\b[A-Za-zÀ-ÿ]{4,}\b", text)
    for word in words:
        word_lower = word.lower()
        if word_lower in KNOWN_TYPOS and word_lower not in seen:
            seen.add(word_lower)
            found.append({"word": word, "suggestion": KNOWN_TYPOS[word_lower]})
    return found


def detect_spelling_warnings(raw_text: str, cv: CvAnalysisResult, ev: CvEvaluation) -> None:
    if not raw_text:
        return

    spell_text = _strip_contact_data(raw_text)

    # Step 1: deterministic regex check for known patterns
    regex_typos = _regex_spell_check(spell_text)

    # Step 2: Mistral check for anything the regex missed
    prompt = f"""You are a professional spell checker reviewing a CV written in English or French (or both).

Your task: find words that are CLEARLY misspelled — missing or transposed letters, wrong spelling of common words.

Focus especially on:
- Words in the profile/summary paragraph
- Job descriptions
- Words that look like they have missing letters (e.g. "systms" missing an 'e', "numrous" missing an 'e')

DO NOT flag:
- Technical terms, programming languages, frameworks (Angular, Docker, OAuth2, etc.)
- Acronyms (REST, UML, SOA, IEEE, ISET, SDGs, etc.)
- Proper nouns: person names, company names, city names, university names
- Words spelled correctly in EITHER English OR French
- ALL CAPS section headers (formatting, not typos)
- Capitalisation differences only

Return ONLY a JSON array. Each item has exactly two keys:
  "word"       — the misspelled word exactly as it appears
  "suggestion" — the correct spelling

Return [] if no mistakes found.

CV TEXT:
{spell_text[:3000]}

Return ONLY the JSON array, nothing else."""

    raw_response = ""
    mistral_typos = []
    try:
        response = ollama_client.chat(
            model="qwen2.5:3b",
            messages=[{"role": "user", "content": prompt}],
            options={"temperature": 0},
        )
        raw_response = response["message"]["content"].strip()
        raw_response = re.sub(r"<think>.*?</think>", "", raw_response, flags=re.DOTALL).strip()
        raw_response = re.sub(r"```json\s*|```\s*", "", raw_response).strip()
        result = json.loads(raw_response)
        if isinstance(result, list):
            mistral_typos = result
    except (json.JSONDecodeError, Exception):
        m = re.search(r"\[.*\]", raw_response, re.DOTALL)
        try:
            mistral_typos = json.loads(m.group(0)) if m else []
        except Exception:
            mistral_typos = []

    # Merge: regex findings take priority, Mistral fills in the rest
    # Deduplicate by word (case-insensitive)
    seen_words = set()
    all_typos = []

    for t in regex_typos:
        word_lower = t.get("word", "").lower()
        if word_lower and word_lower not in seen_words:
            seen_words.add(word_lower)
            all_typos.append(t)

    for t in mistral_typos:
        word = t.get("word", "")
        suggestion = t.get("suggestion", "")
        word_lower = word.lower()
        # Skip if already found by regex, identical to suggestion, or is a known false positive
        if (not word or not suggestion or
                word_lower == suggestion.lower() or
                word_lower in seen_words):
            continue
        seen_words.add(word_lower)
        all_typos.append(t)

    displayed = all_typos[:5]
    ev.likely_typos_count = len(displayed)
    for t in displayed:
        word, suggestion = t.get("word", ""), t.get("suggestion", "")
        if word and suggestion and word.lower() != suggestion.lower():
            ev.spelling_warnings.append(
                f"Possible typo: '{word}' → did you mean '{suggestion}'?"
            )


def detect_technical_profile(cv: CvAnalysisResult, ev: CvEvaluation) -> None:
    detected = [s for s in cv.skills if s in TECH_STACK]
    if len(detected) >= 3:
        _append_unique(ev.profile_strengths, "Strong technical stack detected")
    if "Angular" in cv.skills and "Spring Boot" in cv.skills:
        _append_unique(ev.profile_strengths, "Full-stack development experience detected")
    if "Flutter" in cv.skills:
        _append_unique(ev.profile_strengths, "Mobile development experience detected")
    if cv.work_experience and any(e.skills_used for e in cv.work_experience):
        _append_unique(ev.profile_strengths, "Practical technology usage detected in experience")


def detect_competition_strength(cv: CvAnalysisResult, ev: CvEvaluation) -> None:
    if cv.hackathons:
        _append_unique(ev.profile_strengths, "Competition achievements detected through hackathons")
    for h in cv.hackathons:
        if h.rank and "1" in h.rank:
            _append_unique(ev.profile_strengths, "Top competition ranking detected")
            break
    if cv.awards:
        _append_unique(ev.profile_strengths, "Awards or recognitions detected")


def detect_leadership(cv: CvAnalysisResult, ev: CvEvaluation) -> None:
    if cv.volunteer_work:
        _append_unique(ev.profile_strengths, "Leadership or community involvement detected")
    keywords = {"chair", "lead", "president", "ambassador", "organizer", "manager",
                "captain", "treasurer", "trésorier", "président"}
    for vol in cv.volunteer_work:
        if any(kw in (vol.role or "").lower() for kw in keywords):
            _append_unique(ev.profile_strengths, "Leadership role detected in volunteer activities")
            break


def build_recruiter_insights(cv: CvAnalysisResult, ev: CvEvaluation) -> None:
    if cv.skills:
        ev.recruiter_insights.append(f"Key technical skills include: {', '.join(cv.skills[:5])}")
    if cv.work_experience:
        n = len(cv.work_experience)
        ev.recruiter_insights.append(
            f"Candidate has {n} professional experience entr{'y' if n == 1 else 'ies'}"
        )
    if cv.hackathons:
        n = len(cv.hackathons)
        ev.recruiter_insights.append(
            f"Candidate participated in {n} hackathon/competition event{'s' if n != 1 else ''}"
        )
    if cv.awards:
        ev.recruiter_insights.append("Candidate has received awards or recognitions")
    if cv.volunteer_work:
        ev.recruiter_insights.append(
            "Candidate shows extracurricular engagement through volunteer or leadership roles"
        )
    if not cv.projects:
        ev.recruiter_insights.append("No standalone development projects detected")
    if not ev.has_github:
        ev.recruiter_insights.append(
            "No public GitHub repository provided for technical verification"
        )
    if ev.experience_gap_count > 0:
        msg = ("Breaks in listed internship experience were detected and may be explained "
               "by ongoing studies" if is_student_profile(cv)
               else "Timeline gaps detected and should be reviewed manually")
        ev.recruiter_insights.append(msg)
    if is_student_profile(cv):
        ev.recruiter_insights.append(
            "Profile appears aligned with an internship or junior candidate"
        )


def build_evidence_signals(cv: CvAnalysisResult) -> EvidenceSignals:
    signals = EvidenceSignals()
    if len(cv.skills) >= 5:   signals.technical_evidence = "HIGH"
    elif len(cv.skills) >= 3: signals.technical_evidence = "MEDIUM"
    if cv.projects:           signals.project_evidence = "HIGH"
    elif cv.hackathons:       signals.project_evidence = "MEDIUM"
    if cv.hackathons:         signals.competition_evidence = "HIGH"
    if cv.volunteer_work:     signals.leadership_evidence = "HIGH"
    if cv.social_links and (cv.social_links.github or cv.social_links.portfolio):
        signals.public_portfolio_evidence = "HIGH"
    return signals


def evaluate_cv(cv: CvAnalysisResult, raw_text: str) -> CvEvaluation:
    ev = CvEvaluation()
    detect_missing_sections(cv, ev)
    detect_completeness(cv, ev)
    detect_structure_warnings(cv, ev)
    detect_date_warnings_and_gaps(cv, ev)
    detect_spelling_warnings(raw_text, cv, ev)
    detect_technical_profile(cv, ev)
    detect_competition_strength(cv, ev)
    detect_leadership(cv, ev)
    build_recruiter_insights(cv, ev)
    ev.evidence_signals = build_evidence_signals(cv)
    return ev