import re
from datetime import date
from typing import Optional, Tuple, List

from app.models import CvAnalysisResult, CvEvaluation, EvidenceSignals


MONTHS = {
    "jan": 1, "january": 1,
    "feb": 2, "february": 2,
    "mar": 3, "march": 3,
    "apr": 4, "april": 4,
    "may": 5,
    "jun": 6, "june": 6,
    "jul": 7, "july": 7,
    "aug": 8, "august": 8,
    "sep": 9, "sept": 9, "september": 9,
    "oct": 10, "october": 10,
    "nov": 11, "november": 11,
    "dec": 12, "december": 12,
}

COMMON_WORDS = {
    "developpement", "managment", "ingenieur"
}

TECH_STACK = {
    "Angular", "React", "Vue", "Spring Boot", "Node", "Python", "Java",
    "Flutter", "Docker", "Kubernetes", "MySQL", "PostgreSQL", "MongoDB",
    "TypeScript", "JavaScript", "C#", ".NET", "FastAPI", "Django"
}


def _append_unique(target: List[str], value: str) -> None:
    if value and value not in target:
        target.append(value)


def parse_month_year(part: str) -> Optional[date]:
    if not part:
        return None

    part = part.strip().lower()

    if part in {"present", "current", "now", "today"}:
        today = date.today()
        return date(today.year, today.month, 1)

    m = re.search(r"([A-Za-z]+)\s+(\d{4})", part)
    if m:
        month_name = m.group(1).lower()
        year = int(m.group(2))
        month = MONTHS.get(month_name)
        if month:
            return date(year, month, 1)

    m = re.search(r"(\d{4})", part)
    if m:
        year = int(m.group(1))
        return date(year, 1, 1)

    return None


def parse_duration_range(duration: Optional[str]) -> Tuple[Optional[date], Optional[date]]:
    if not duration:
        return None, None

    text = duration.strip().lower().replace("–", "-").replace("—", "-")

    # French numeric format: Du 06/01/2025 au 01/02/2025
    m = re.search(r"(\d{2})/(\d{2})/(\d{4}).*?(\d{2})/(\d{2})/(\d{4})", text)
    if m:
        start = date(int(m.group(3)), int(m.group(2)), int(m.group(1)))
        end = date(int(m.group(6)), int(m.group(5)), int(m.group(4)))
        return start, end

    # year-year format: 2023-2026
    m = re.search(r"(\d{4})\s*-\s*(\d{4})", text)
    if m:
        start = date(int(m.group(1)), 1, 1)
        end = date(int(m.group(2)), 1, 1)
        return start, end

    parts = [p.strip() for p in text.split("-") if p.strip()]
    if len(parts) >= 2:
        return parse_month_year(parts[0]), parse_month_year(parts[1])

    single = parse_month_year(text)
    return single, None


def months_between(d1: date, d2: date) -> int:
    return (d2.year - d1.year) * 12 + (d2.month - d1.month)


def is_student_profile(cv: CvAnalysisResult) -> bool:
    if cv.seniority_level == "INTERN":
        return True

    for edu in cv.education:
        if edu.year and "present" in edu.year.lower():
            return True

    return False


def detect_missing_sections(cv: CvAnalysisResult, ev: CvEvaluation) -> None:
    if not cv.summary:
        ev.missing_sections.append("Summary")
    if not cv.skills:
        ev.missing_sections.append("Skills")
    if not cv.education:
        ev.missing_sections.append("Education")
    if not cv.work_experience:
        ev.missing_sections.append("Work Experience")
    if not cv.projects:
        ev.missing_sections.append("Projects")
    if not cv.languages:
        ev.missing_sections.append("Languages")


def detect_completeness(cv: CvAnalysisResult, ev: CvEvaluation) -> None:
    ev.has_email = bool(cv.email)
    ev.has_phone = bool(cv.phone)
    ev.has_linkedin = bool(cv.social_links and cv.social_links.linkedin)
    ev.has_github = bool(cv.social_links and cv.social_links.github)
    ev.has_portfolio = bool(cv.social_links and cv.social_links.portfolio)
    ev.has_projects = len(cv.projects) > 0
    ev.has_experience = len(cv.work_experience) > 0
    ev.has_education = len(cv.education) > 0
    ev.has_skills = len(cv.skills) > 0
    ev.has_languages = len(cv.languages) > 0

    if not ev.has_email:
        _append_unique(ev.profile_weaknesses, "Missing email")
    if not ev.has_phone:
        _append_unique(ev.profile_weaknesses, "Missing phone number")
    if not ev.has_linkedin:
        _append_unique(ev.profile_weaknesses, "No LinkedIn profile provided")
    if not ev.has_github:
        _append_unique(ev.profile_weaknesses, "No GitHub profile provided")
    if not ev.has_projects:
        _append_unique(ev.profile_weaknesses, "No standalone projects listed")

    if ev.has_skills:
        _append_unique(ev.profile_strengths, "Technical skills section detected")
    if ev.has_education:
        _append_unique(ev.profile_strengths, "Education history detected")
    if ev.has_experience:
        _append_unique(ev.profile_strengths, "Work experience detected")
    if ev.has_languages:
        _append_unique(ev.profile_strengths, "Languages section detected")


def detect_structure_warnings(cv: CvAnalysisResult, ev: CvEvaluation) -> None:
    for exp in cv.work_experience:
        missing = []
        if not exp.title:
            missing.append("title")
        if not exp.company:
            missing.append("company")
        if not exp.duration:
            missing.append("duration")
        if not exp.description:
            missing.append("description")

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
        missing = []
        if not edu.degree:
            missing.append("degree")
        if not edu.institution:
            missing.append("institution")
        if not edu.year:
            missing.append("year")

        if missing:
            ev.incomplete_education_entries_count += 1
            ev.structure_warnings.append(
                f"Education entry is incomplete: missing {', '.join(missing)}"
            )


def detect_date_warnings_and_gaps(cv: CvAnalysisResult, ev: CvEvaluation) -> None:
    parsed_ranges: List[Tuple[date, date, str]] = []
    student_profile = is_student_profile(cv)

    for exp in cv.work_experience:
        start, end = parse_duration_range(exp.duration)

        if exp.duration and not start:
            ev.date_warnings.append(
                f"Could not parse start date for experience '{exp.title or 'Untitled'}'"
            )

        if exp.duration and "present" not in exp.duration.lower() and not end:
            ev.date_warnings.append(
                f"Could not parse end date for experience '{exp.title or 'Untitled'}'"
            )

        if start and end and start > end:
            ev.date_warnings.append(
                f"Invalid date range in experience '{exp.title or 'Untitled'}': start date is after end date"
            )

        if start:
            parsed_ranges.append((start, end or date.today(), exp.title or "Untitled"))

    parsed_ranges.sort(key=lambda x: x[0])

    for i in range(len(parsed_ranges) - 1):
        _, current_end, current_title = parsed_ranges[i]
        next_start, _, next_title = parsed_ranges[i + 1]

        gap_months = months_between(current_end, next_start)

        if gap_months > 4:
            ev.experience_gap_count += 1
            if student_profile:
                ev.gap_warnings.append(
                    f"Gap of {gap_months} months between '{current_title}' and '{next_title}' (may be due to ongoing studies)"
                )
            else:
                ev.gap_warnings.append(
                    f"Possible professional timeline gap of {gap_months} months between '{current_title}' and '{next_title}'"
                )

        if current_end > next_start:
            ev.date_warnings.append(
                f"Possible overlap between '{current_title}' and '{next_title}'"
            )


def detect_spelling_warnings(raw_text: str, ev: CvEvaluation) -> None:
    if not raw_text:
        return

    words = re.findall(r"\b[A-Za-z]{4,}\b", raw_text)
    suspicious = []

    for w in words:
        if w.lower() in COMMON_WORDS:
            suspicious.append(w)

    unique_suspicious = sorted(set(suspicious))
    ev.likely_typos_count = len(unique_suspicious)

    for typo in unique_suspicious[:5]:
        ev.spelling_warnings.append(f"Possible typo detected: '{typo}'")


def detect_technical_profile(cv: CvAnalysisResult, ev: CvEvaluation) -> None:
    detected = [skill for skill in cv.skills if skill in TECH_STACK]

    if len(detected) >= 3:
        _append_unique(ev.profile_strengths, "Strong technical stack detected")

    if "Angular" in cv.skills and "Spring Boot" in cv.skills:
        _append_unique(ev.profile_strengths, "Full-stack development experience detected")

    if "Flutter" in cv.skills:
        _append_unique(ev.profile_strengths, "Mobile development experience detected")

    if len(cv.work_experience) > 0 and any(exp.skills_used for exp in cv.work_experience):
        _append_unique(ev.profile_strengths, "Practical technology usage detected in experience")


def detect_competition_strength(cv: CvAnalysisResult, ev: CvEvaluation) -> None:
    if len(cv.hackathons) > 0:
        _append_unique(ev.profile_strengths, "Competition achievements detected through hackathons")

    for hackathon in cv.hackathons:
        if hackathon.rank and "1" in hackathon.rank:
            _append_unique(ev.profile_strengths, "Top competition ranking detected")
            break

    if len(cv.awards) > 0:
        _append_unique(ev.profile_strengths, "Awards or recognitions detected")


def detect_leadership(cv: CvAnalysisResult, ev: CvEvaluation) -> None:
    if len(cv.volunteer_work) > 0:
        _append_unique(ev.profile_strengths, "Leadership or community involvement detected")

    leadership_keywords = {"chair", "lead", "president", "ambassador", "organizer", "manager", "captain"}

    for vol in cv.volunteer_work:
        role = (vol.role or "").lower()
        if any(keyword in role for keyword in leadership_keywords):
            _append_unique(ev.profile_strengths, "Leadership role detected in volunteer activities")
            break


def build_recruiter_insights(cv: CvAnalysisResult, ev: CvEvaluation) -> None:
    if cv.skills:
        ev.recruiter_insights.append(
            f"Key technical skills include: {', '.join(cv.skills[:5])}"
        )

    if len(cv.work_experience) > 0:
        ev.recruiter_insights.append(
            f"Candidate has {len(cv.work_experience)} professional experience entr{'y' if len(cv.work_experience) == 1 else 'ies'}"
        )

    if len(cv.hackathons) > 0:
        ev.recruiter_insights.append(
            f"Candidate participated in {len(cv.hackathons)} hackathon/competition event{'s' if len(cv.hackathons) != 1 else ''}"
        )

    if len(cv.awards) > 0:
        ev.recruiter_insights.append(
            "Candidate has received awards or recognitions"
        )

    if len(cv.volunteer_work) > 0:
        ev.recruiter_insights.append(
            "Candidate shows extracurricular engagement through volunteer or leadership roles"
        )

    if not cv.projects:
        ev.recruiter_insights.append(
            "No standalone development projects detected"
        )

    if not (cv.social_links and cv.social_links.github):
        ev.recruiter_insights.append(
            "No public GitHub repository provided for technical verification"
        )

    if ev.experience_gap_count > 0:
        if is_student_profile(cv):
            ev.recruiter_insights.append(
                "Breaks in listed internship experience were detected and may be explained by ongoing studies"
            )
        else:
            ev.recruiter_insights.append(
                "Timeline gaps detected and should be reviewed manually"
            )

    if is_student_profile(cv):
        ev.recruiter_insights.append(
            "Profile appears aligned with an internship or junior candidate"
        )


def build_evidence_signals(cv: CvAnalysisResult) -> EvidenceSignals:
    signals = EvidenceSignals()

    if len(cv.skills) >= 5:
        signals.technical_evidence = "HIGH"
    elif len(cv.skills) >= 3:
        signals.technical_evidence = "MEDIUM"

    if len(cv.projects) > 0:
        signals.project_evidence = "HIGH"
    elif len(cv.hackathons) > 0:
        signals.project_evidence = "MEDIUM"

    if len(cv.hackathons) > 0:
        signals.competition_evidence = "HIGH"

    if len(cv.volunteer_work) > 0:
        signals.leadership_evidence = "HIGH"

    if cv.social_links and (cv.social_links.github or cv.social_links.portfolio):
        signals.public_portfolio_evidence = "HIGH"

    return signals


def evaluate_cv(cv: CvAnalysisResult, raw_text: str) -> CvEvaluation:
    ev = CvEvaluation()

    detect_missing_sections(cv, ev)
    detect_completeness(cv, ev)
    detect_structure_warnings(cv, ev)
    detect_date_warnings_and_gaps(cv, ev)
    detect_spelling_warnings(raw_text, ev)

    detect_technical_profile(cv, ev)
    detect_competition_strength(cv, ev)
    detect_leadership(cv, ev)

    build_recruiter_insights(cv, ev)
    ev.evidence_signals = build_evidence_signals(cv)

    return ev