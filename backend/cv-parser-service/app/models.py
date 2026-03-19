from pydantic import BaseModel, Field
from typing import List, Optional


class WorkExperience(BaseModel):
    title: Optional[str] = None
    company: Optional[str] = None
    duration: Optional[str] = None
    description: Optional[str] = None
    skills_used: List[str] = Field(default_factory=list)


class Education(BaseModel):
    degree: Optional[str] = None
    institution: Optional[str] = None
    year: Optional[str] = None
    field: Optional[str] = None
    mention: Optional[str] = None


class Language(BaseModel):
    name: str
    level: Optional[str] = None


class SocialLinks(BaseModel):
    linkedin: Optional[str] = None
    github: Optional[str] = None
    portfolio: Optional[str] = None


class Hackathon(BaseModel):
    title: str
    rank: Optional[str] = None
    date: Optional[str] = None
    description: Optional[str] = None
    skills_used: List[str] = Field(default_factory=list)


class Project(BaseModel):
    title: str
    description: Optional[str] = None
    skills_used: List[str] = Field(default_factory=list)
    url: Optional[str] = None


class VolunteerWork(BaseModel):
    role: str
    organization: Optional[str] = None
    duration: Optional[str] = None
    description: Optional[str] = None


class EvidenceSignals(BaseModel):
    technical_evidence: str = "LOW"
    project_evidence: str = "LOW"
    leadership_evidence: str = "LOW"
    competition_evidence: str = "LOW"
    public_portfolio_evidence: str = "LOW"


class CvEvaluation(BaseModel):
    missing_sections: List[str] = Field(default_factory=list)
    structure_warnings: List[str] = Field(default_factory=list)
    spelling_warnings: List[str] = Field(default_factory=list)
    date_warnings: List[str] = Field(default_factory=list)
    gap_warnings: List[str] = Field(default_factory=list)

    profile_strengths: List[str] = Field(default_factory=list)
    profile_weaknesses: List[str] = Field(default_factory=list)
    recruiter_insights: List[str] = Field(default_factory=list)

    likely_typos_count: int = 0
    experience_gap_count: int = 0
    incomplete_experience_entries_count: int = 0
    incomplete_education_entries_count: int = 0

    has_email: bool = False
    has_phone: bool = False
    has_linkedin: bool = False
    has_github: bool = False
    has_portfolio: bool = False
    has_projects: bool = False
    has_experience: bool = False
    has_education: bool = False
    has_skills: bool = False
    has_languages: bool = False

    evidence_signals: Optional[EvidenceSignals] = None


class CvAnalysisResult(BaseModel):
    application_id: str

    candidate_name: Optional[str] = None
    email: Optional[str] = None
    phone: Optional[str] = None
    location: Optional[str] = None
    social_links: Optional[SocialLinks] = None
    summary: Optional[str] = None
    desired_position: Optional[str] = None
    availability: Optional[str] = None

    skills: List[str] = Field(default_factory=list)
    soft_skills: List[str] = Field(default_factory=list)

    languages: List[Language] = Field(default_factory=list)

    certifications: List[str] = Field(default_factory=list)

    work_experience: List[WorkExperience] = Field(default_factory=list)
    total_years_experience: Optional[float] = None
    seniority_level: Optional[str] = None

    education: List[Education] = Field(default_factory=list)

    projects: List[Project] = Field(default_factory=list)
    hackathons: List[Hackathon] = Field(default_factory=list)
    volunteer_work: List[VolunteerWork] = Field(default_factory=list)
    awards: List[str] = Field(default_factory=list)

    raw_text_length: Optional[int] = None
    parsing_status: str = "SUCCESS"
    error_message: Optional[str] = None

    evaluation: Optional[CvEvaluation] = None