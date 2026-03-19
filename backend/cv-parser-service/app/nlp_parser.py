import re
import json
import os
import ollama
from typing import Optional

from app.evaluator import evaluate_cv
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

import spacy

nlp = spacy.load("en_core_web_sm")

OLLAMA_HOST = os.getenv("OLLAMA_HOST", "http://localhost:11434")
ollama_client = ollama.Client(host=OLLAMA_HOST)

SENIORITY_MAP = {
    "intern": "INTERN", "internship": "INTERN",
    "stage": "INTERN", "stagiaire": "INTERN",
    "junior": "JUNIOR",
    "mid": "MID", "intermediate": "MID",
    "senior": "SENIOR", "lead": "SENIOR",
    "principal": "SENIOR", "architect": "SENIOR",
    "manager": "SENIOR", "director": "SENIOR",
}


def extract_email(text: str) -> Optional[str]:
    text = re.sub(r"([a-zA-Z0-9._%+-])\n(@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,})", r"\1\2", text)
    match = re.search(r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}", text)
    return match.group(0) if match else None


def extract_phone(text: str) -> Optional[str]:
    match = re.search(r"(\+?\d[\d\s\-\(\)]{7,}\d)", text)
    return match.group(0).strip() if match else None


def extract_seniority(text: str) -> Optional[str]:
    text_lower = text.lower()
    for keyword, level in SENIORITY_MAP.items():
        if keyword in text_lower:
            return level
    return None


def extract_with_mistral(text: str) -> dict:
    prompt = f"""You are an expert CV parser. Extract ALL possible information from the CV below and return ONLY a valid JSON object with no explanation, no markdown, no extra text.

Return exactly this JSON structure:
{{
  "candidate_name": "full name or null",
  "location": "candidate home city/country or null",
  "summary": "brief profile summary in 1-2 sentences or null",
  "desired_position": "the position the candidate is looking for or null",
  "availability": "immediate / date / null",
  "social_links": {{
    "linkedin": "full linkedin URL or null",
    "github": "full github URL or null",
    "portfolio": "portfolio/website URL or null"
  }},
  "skills": ["skill1", "skill2"],
  "soft_skills": ["leadership", "teamwork"],
  "languages": [
    {{"name": "English", "level": "Advanced"}},
    {{"name": "French", "level": "Professional"}}
  ],
  "certifications": ["cert1"],
  "education": [
    {{
      "degree": "degree title",
      "institution": "school name or null",
      "year": "year or null",
      "field": "field of study or null",
      "mention": "honors/GPA/mention or null"
    }}
  ],
  "work_experience": [
    {{
      "title": "job title",
      "company": "company name or null",
      "duration": "date range or null",
      "description": "description or null",
      "skills_used": ["skill1", "skill2"]
    }}
  ],
  "projects": [
    {{
      "title": "project name",
      "description": "description or null",
      "skills_used": ["skill1", "skill2"],
      "url": "project URL or null"
    }}
  ],
  "hackathons": [
    {{
      "title": "hackathon name",
      "rank": "1st place or null",
      "date": "date or null",
      "description": "description or null",
      "skills_used": ["skill1"]
    }}
  ],
  "volunteer_work": [
    {{
      "role": "role title",
      "organization": "organization name or null",
      "duration": "date range or null",
      "description": "description or null"
    }}
  ],
  "awards": ["award1", "award2"],
  "total_years_experience": 0
}}

Rules:
- For candidate location: extract the candidate HOME city/address only. Do NOT use company or school addresses.
  Example: if CV says "Address: Tunis (Menzah 8, Ariana)" -> location is "Menzah 8, Ariana, Tunis".
  Do NOT use internship company addresses like "Lac 1, Tunis" or "Lac 2, Tunis".

- For languages: extract EVERY language listed. Do NOT skip any language.
  IMPORTANT: due to multi-column PDF layout, the language name and its level may appear on SEPARATE lines.
  Always pair them correctly by reading consecutive lines:
  * "Arabic" followed by "Native language" on next line -> {{"name": "Arabic", "level": "Native"}}
  * "French" followed by "Professional proficiency" -> {{"name": "French", "level": "Professional"}}
  * "English" followed by "Advanced proficiency" -> {{"name": "English", "level": "Advanced"}}
  * "Spanish" followed by "Beginner" on next line -> {{"name": "Spanish", "level": "Beginner"}}
  * NEVER skip a language just because its level appears on a separate line.
  * Read EACH language and its EXACT level. Do NOT swap or mix levels between languages.
  If no level mentioned, set level to null.

- For hackathons vs projects — STRICT SEPARATION:
  * "hackathons" array: ANY event that is a competition, hackathon, contest, challenge, or where a rank/prize was won.
    Look for keywords: "hackathon", "competition", "contest", "place", "prize", "1st", "2nd", "3rd", "winner".
  * "projects" array: ONLY personal or academic DEVELOPMENT projects with no competition involved.
  * NEVER put a hackathon inside "projects". NEVER put a project inside "hackathons".

- For hackathon dates: the date appears at the START of the line before the hackathon title.
  Example: "Apr 2025 | 1st Place, AI for SDGs Hackathon" -> date is "Apr 2025", rank is "1st place"
  Example: "Nov 2023 | 2nd Place, ISET Nabeul..." -> date is "Nov 2023", rank is "2nd place"
  ALWAYS extract the date prefix as the hackathon date. Never leave date as null if it appears in the line.

- For skills: extract ALL technical skills from the ENTIRE CV:
  * From the dedicated skills section
  * From work experience descriptions
  * From project descriptions
  * From hackathon descriptions
  * IMPORTANT: due to multi-column PDF, skills may appear one per line — read ALL of them.
  Remove duplicates, return a clean unique list.

- For soft_skills: extract interpersonal/behavioral skills like leadership, teamwork, communication,
  creativity, initiative, autonomy, rigor, problem-solving, adaptability, reliability, etc.
  Also extract from the profile/summary section — words like "rigorous", "reliable", "creative",
  "initiative" should be included as soft skills.

- For volunteer_work: include ALL associative/extracurricular entries — any non-professional
  engagement such as associations, clubs, student organizations, ambassador roles,
  representation, community work, NGOs, or student-led activities.
  * Extract EVERY entry separately, even if grouped under the same section.
  * For organization: read the role title and description carefully and infer the organization
    name from the context. Do NOT rely on a predefined list — use your understanding of the text.
    If the organization truly cannot be inferred from any context, set to null.
  * For role: use the exact title as written in the CV.
  * NEVER skip an entry — extract ALL roles listed even under the same section heading.

- For awards: include ALL prizes, recognitions, achievements, "Outstanding contribution", rankings.
  Also extract hackathon prizes as awards (e.g. "1st Place, AI for SDGs Hackathon").

- For mention in education: extract "Valedictorian", "Major de promotion", "mention bien/tres bien", "Honors", GPA.

- For desired_position: extract what the candidate is looking for (e.g. "Stage PFE", "Full Stack Developer").

- For availability: extract if mentioned (e.g. "immediately available", "available from June 2025").

- For summary: write 1-2 sentences summarizing the candidate based on their profile section.

- For social_links: reconstruct full URLs if needed.
  Example: "linkedin.com/in/xxx" -> "https://linkedin.com/in/xxx"
  Example: "github.com/xxx" -> "https://github.com/xxx"

- Use null (NOT the string "null") for missing values.

- total_years_experience: calculate from work experience dates. Return 0 if student with no experience.

CV TEXT:
{text[:5000]}

Return ONLY the JSON, nothing else."""

    raw = ""  # initialise AVANT le try pour être accessible dans except
    try:
        response = ollama_client.chat(
            model="mistral",
            messages=[{"role": "user", "content": prompt}],
            options={"temperature": 0}
        )
        raw = response["message"]["content"].strip()
        raw = re.sub(r"```json\s*", "", raw)
        raw = re.sub(r"```\s*", "", raw)
        raw = raw.strip()
        return json.loads(raw)

    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", raw, re.DOTALL)
        if match:
            try:
                return json.loads(match.group(0))
            except json.JSONDecodeError:
                pass
        return {}
    except Exception as e:
        raise RuntimeError(f"Mistral extraction failed: {str(e)}")


def _safe(val, is_list: bool = False):
    if val is None or val == "null" or val == "":
        return None
    if is_list:
        if not isinstance(val, list):
            return []
        return [v for v in val if v and v != "null"]
    return val


def parse_cv(text: str, application_id: str) -> CvAnalysisResult:
    try:
        email = extract_email(text)
        phone = extract_phone(text)
        seniority = extract_seniority(text)
        mistral_data = extract_with_mistral(text)

        education_list = []
        for edu in mistral_data.get("education", []):
            education_list.append(Education(
                degree=_safe(edu.get("degree")),
                institution=_safe(edu.get("institution")),
                year=_safe(str(edu.get("year"))) if edu.get("year") else None,
                field=_safe(edu.get("field")),
                mention=_safe(edu.get("mention")),
            ))

        experience_list = []
        for exp in mistral_data.get("work_experience", []):
            experience_list.append(WorkExperience(
                title=_safe(exp.get("title")),
                company=_safe(exp.get("company")),
                duration=_safe(exp.get("duration")),
                description=_safe(exp.get("description")),
                skills_used=_safe(exp.get("skills_used", []), is_list=True) or [],
            ))

        languages_list = []
        for lang in mistral_data.get("languages", []):
            if isinstance(lang, dict):
                languages_list.append(Language(
                    name=lang.get("name", ""),
                    level=_safe(lang.get("level")),
                ))
            elif isinstance(lang, str):
                languages_list.append(Language(name=lang, level=None))

        projects_list = []
        for proj in mistral_data.get("projects", []):
            projects_list.append(Project(
                title=proj.get("title", ""),
                description=_safe(proj.get("description")),
                skills_used=_safe(proj.get("skills_used", []), is_list=True) or [],
                url=_safe(proj.get("url")),
            ))

        hackathons_list = []
        for hack in mistral_data.get("hackathons", []):
            hackathons_list.append(Hackathon(
                title=hack.get("title", ""),
                rank=_safe(hack.get("rank")),
                date=_safe(hack.get("date")),
                description=_safe(hack.get("description")),
                skills_used=_safe(hack.get("skills_used", []), is_list=True) or [],
            ))

        volunteer_list = []
        for vol in mistral_data.get("volunteer_work", []):
            volunteer_list.append(VolunteerWork(
                role=vol.get("role", ""),
                organization=_safe(vol.get("organization")),
                duration=_safe(vol.get("duration")),
                description=_safe(vol.get("description")),
            ))

        social_raw = mistral_data.get("social_links", {}) or {}
        social_links = SocialLinks(
            linkedin=_safe(social_raw.get("linkedin")),
            github=_safe(social_raw.get("github")),
            portfolio=_safe(social_raw.get("portfolio")),
        )

        result = CvAnalysisResult(
            application_id=application_id,
            candidate_name=_safe(mistral_data.get("candidate_name")),
            email=email or _safe(mistral_data.get("email")),
            phone=phone or _safe(mistral_data.get("phone")),
            location=_safe(mistral_data.get("location")),
            social_links=social_links,
            summary=_safe(mistral_data.get("summary")),
            desired_position=_safe(mistral_data.get("desired_position")),
            availability=_safe(mistral_data.get("availability")),
            skills=_safe(mistral_data.get("skills", []), is_list=True) or [],
            soft_skills=_safe(mistral_data.get("soft_skills", []), is_list=True) or [],
            languages=languages_list,
            certifications=_safe(mistral_data.get("certifications", []), is_list=True) or [],
            work_experience=experience_list,
            total_years_experience=mistral_data.get("total_years_experience"),
            seniority_level=seniority,
            education=education_list,
            projects=projects_list,
            hackathons=hackathons_list,
            volunteer_work=volunteer_list,
            awards=_safe(mistral_data.get("awards", []), is_list=True) or [],
            raw_text_length=len(text),
            parsing_status="SUCCESS",
        )

        result.evaluation = evaluate_cv(result, text)
        return result

    except Exception as e:
        return CvAnalysisResult(
            application_id=application_id,
            parsing_status="FAILED",
            error_message=str(e),
        )