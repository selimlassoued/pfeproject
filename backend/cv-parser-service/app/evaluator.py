import re
import json
import os
from datetime import date
from typing import Optional, Tuple, List

import ollama

from app.models import CvAnalysisResult, CvEvaluation, EvidenceSignals

OLLAMA_HOST   = os.getenv("OLLAMA_HOST", "http://localhost:11434")
MODEL         = os.getenv("CV_PARSER_MODEL", "qwen2.5:3b")
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

# GitHub scores that mean "we simply cannot assess" — treat identically to no GitHub provided
_UNASSESSABLE_SCORES = {"NO_PUBLIC_WORK", "RATE_LIMITED"}


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
    KNOWN_TYPOS = {
        "developper": "developer", "developpeur": "développeur",
        "architctures": "architectures", "architecure": "architecture",
        "systms": "systems", "sytem": "system",
        "numrous": "numerous", "professionnal": "professional",
        "experiense": "experience", "expérience": "expérience",
        "managment": "management", "devlopment": "development",
        "knowlegde": "knowledge", "recrutment": "recruitment",
        "recrutement": "recrutement",
        "biginner": "beginner", "begginer": "beginner",
        "intermediaire": "intermédiaire",
        "treausrer": "treasurer", "tresurer": "treasurer",
        "colaborer": "collaborer", "colaborate": "collaborate",
        "compétencs": "compétences",
    }
    found = []
    seen = set()
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
    regex_typos = _regex_spell_check(spell_text)

    prompt = f"""You are a professional spell checker reviewing a CV in English or French.

Find words that are CLEARLY misspelled — missing or transposed letters only.

DO NOT flag:
- Technical terms, frameworks, programming languages (Angular, Docker, OAuth2, etc.)
- Acronyms (REST, UML, IEEE, ISET, SDGs, etc.)
- Proper nouns: names, companies, cities, universities
- Words correct in English OR French
- ALL CAPS section headers

Return ONLY a JSON array with items having "word" and "suggestion" keys.
Return [] if no mistakes found.

CV TEXT:
{spell_text[:3000]}

Return ONLY the JSON array, nothing else."""

    raw_response = ""
    llm_typos = []
    try:
        response = ollama_client.chat(
            model=MODEL,
            messages=[{"role": "user", "content": prompt}],
            options={"temperature": 0},
        )
        raw_response = response["message"]["content"].strip()
        raw_response = re.sub(r"<think>.*?</think>", "", raw_response, flags=re.DOTALL).strip()
        raw_response = re.sub(r"```json\s*|```\s*", "", raw_response).strip()
        result = json.loads(raw_response)
        if isinstance(result, list):
            llm_typos = result
    except Exception:
        m = re.search(r"\[.*\]", raw_response, re.DOTALL)
        try:
            llm_typos = json.loads(m.group(0)) if m else []
        except Exception:
            llm_typos = []

    seen_words = set()
    all_typos  = []

    for t in regex_typos:
        word_lower = t.get("word", "").lower()
        if word_lower and word_lower not in seen_words:
            seen_words.add(word_lower)
            all_typos.append(t)

    for t in llm_typos:
        word       = t.get("word", "")
        suggestion = t.get("suggestion", "")
        word_lower = word.lower()
        if (not word or not suggestion
                or word_lower == suggestion.lower()
                or word_lower in seen_words):
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


def _is_github_assessable(cv: CvAnalysisResult) -> bool:
    """
    Returns True only when we have a GitHub profile with real scoring data.
    NO_PUBLIC_WORK, RATE_LIMITED, and missing profiles all return False —
    these candidates must not be penalized for lack of public GitHub data.
    """
    if not cv.github_profile:
        return False
    score = cv.github_profile.github_score
    return score not in _UNASSESSABLE_SCORES


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

    # ── GitHub-based insights — only when profile is actually assessable ──────
    if not _is_github_assessable(cv):
        # Account found but cannot be assessed — inform recruiter neutrally,
        # never penalize the candidate.
        if cv.github_profile:
            score = cv.github_profile.github_score
            if score == "NO_PUBLIC_WORK":
                ev.recruiter_insights.append(
                    "GitHub account found but has no public repositories — "
                    "candidate may work primarily on private projects"
                )
            elif score == "RATE_LIMITED":
                ev.recruiter_insights.append(
                    "GitHub profile could not be fully assessed due to API rate limits"
                )
        return  # ← stop here, no scoring-based signals below

    # From here on: profile is assessable (STRONG / MODERATE / WEAK / INACTIVE)
    gh = cv.github_profile
    score = gh.github_score

    if score == "STRONG":
        _append_unique(ev.profile_strengths, "GitHub profile shows strong development activity")
    elif score == "MODERATE":
        _append_unique(ev.profile_strengths, "GitHub profile shows moderate development activity")
    elif score == "WEAK":
        _append_unique(ev.profile_weaknesses, "GitHub account exists but repos appear empty or low quality")
    elif score == "INACTIVE":
        _append_unique(ev.profile_weaknesses, "GitHub account has no public repositories")

    if gh.all_technologies:
        ev.recruiter_insights.append(
            f"GitHub confirms use of: {', '.join(gh.all_technologies[:4])}"
        )
    if gh.real_repos_count > 0:
        ev.recruiter_insights.append(
            f"{gh.real_repos_count} genuine project(s) found on GitHub "
            f"(own code, 10+ commits, real content)"
        )
    if gh.total_stars > 0:
        ev.recruiter_insights.append(
            f"GitHub repos received {gh.total_stars} star(s) from other developers"
        )
    if gh.account_age_days > 365:
        ev.recruiter_insights.append(
            f"GitHub account is {gh.account_age_days // 365} year(s) old "
            f"— established developer presence"
        )
    if gh.scored_repos:
        top = gh.scored_repos[0]
        if top.commit_count >= 10:
            ev.recruiter_insights.append(
                f"Best project: '{top.name}' — "
                f"{top.commit_count} commits, {top.size_kb}KB"
                + (f", {top.stars} ★" if top.stars > 0 else "")
                + (f" — {top.description}" if top.description else "")
            )

    # Commit consistency
    if gh.consistent_repos:
        _append_unique(
            ev.profile_strengths,
            f"Consistent commit activity across {len(gh.consistent_repos)} project(s): "
            f"{', '.join(gh.consistent_repos)}"
        )
    if gh.recently_active_repos > 0:
        ev.recruiter_insights.append(
            f"Recently active: {gh.recently_active_repos} of top repo(s) "
            f"pushed within the last 6 months"
        )

    # Code ownership depth
    if gh.avg_ownership_ratio >= 0.9:
        _append_unique(
            ev.profile_strengths,
            f"Sole author of top projects ({int(gh.avg_ownership_ratio * 100)}% of commits)"
        )
    elif gh.avg_ownership_ratio >= 0.6:
        _append_unique(
            ev.profile_strengths,
            f"Primary contributor to top projects ({int(gh.avg_ownership_ratio * 100)}% of commits)"
        )
    elif 0 < gh.avg_ownership_ratio < 0.3:
        _append_unique(
            ev.profile_weaknesses,
            f"Low authorship ratio on top repos ({int(gh.avg_ownership_ratio * 100)}% of commits) "
            f"— may include inherited code"
        )

    # Project complexity
    high_complexity = [r for r in gh.scored_repos if r.complexity_label == "HIGH"]
    if high_complexity:
        names = ", ".join(f"'{r.name}'" for r in high_complexity[:2])
        _append_unique(ev.profile_strengths, f"High-complexity project(s) detected: {names}")
    else:
        medium_complexity = [r for r in gh.scored_repos if r.complexity_label == "MEDIUM"]
        if medium_complexity:
            _append_unique(
                ev.profile_strengths,
                f"{len(medium_complexity)} medium-complexity project(s) on GitHub"
            )

    # Collaboration signals
    collab = gh.collaboration
    if collab.has_collaboration:
        _append_unique(
            ev.profile_strengths,
            f"Open-source collaboration detected: contributed to "
            f"{collab.active_forks_count} external project(s)"
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

    # GitHub activity evidence:
    # Only produce HIGH/MEDIUM/LOW when the profile is actually assessable.
    # NO_PUBLIC_WORK, RATE_LIMITED, and missing all map to N/A — no penalty.
    if cv.github_profile:
        score = cv.github_profile.github_score
        if score == "STRONG":
            signals.github_activity_evidence = "HIGH"
        elif score == "MODERATE":
            signals.github_activity_evidence = "MEDIUM"
        elif score in ("WEAK", "INACTIVE"):
            signals.github_activity_evidence = "LOW"
        else:
            # NO_PUBLIC_WORK, RATE_LIMITED → N/A (neutral, same as no GitHub)
            signals.github_activity_evidence = "N/A"
    # If no github_profile at all → stays "N/A" (default in EvidenceSignals)

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