from pydantic import BaseModel, Field
from typing import List, Optional, Dict, Any


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


# ─────────────────────────────────────────────────────────────────────────────
# GitHub enrichment models
# ─────────────────────────────────────────────────────────────────────────────

class CommitActivity(BaseModel):
    """Weekly commit pattern for a single repo (last 52 weeks)."""
    weekly_counts: List[int] = Field(default_factory=list)
    active_weeks: int = 0               # weeks with at least 1 commit
    recent_weeks_active: int = 0        # active weeks in the last 12 weeks
    longest_streak: int = 0             # longest consecutive active weeks
    is_consistent: bool = False         # True if active_weeks >= 4


class CollaborationSignals(BaseModel):
    """Signals that the candidate contributes to other people's projects."""
    active_forks_count: int = 0         # forks where candidate made real commits
    collaborated_repos: List[str] = Field(default_factory=list)
    has_collaboration: bool = False


class GitHubRepo(BaseModel):
    """A single repository with quality scoring details."""
    name: Optional[str] = None
    description: Optional[str] = None
    language: Optional[str] = None
    all_languages: List[str] = Field(default_factory=list)
    frameworks: List[str] = Field(default_factory=list)
    technologies: List[str] = Field(default_factory=list)
    stars: int = 0
    url: Optional[str] = None
    is_fork: bool = False
    size_kb: int = 0
    commit_count: int = 0
    branch_count: int = 0
    days_of_activity: int = 0
    has_description: bool = False
    last_pushed: Optional[str] = None
    topics: List[str] = Field(default_factory=list)
    score: int = 0
    is_real: bool = False
    score_reasons: List[str] = Field(default_factory=list)

    # ── New: ownership & activity ─────────────────────────────────────────────
    ownership_ratio: float = 0.0        # candidate's commits / total commits (0.0–1.0)
    commit_activity: CommitActivity = Field(default_factory=CommitActivity)

    # ── New: project complexity ───────────────────────────────────────────────
    complexity_score: int = 0           # 0–10
    complexity_label: str = "LOW"       # HIGH / MEDIUM / LOW
    complexity_reasons: List[str] = Field(default_factory=list)


class GitHubProfile(BaseModel):
    """Enriched GitHub profile."""
    username: Optional[str] = None
    account_url: Optional[str] = None
    name: Optional[str] = None
    bio: Optional[str] = None
    location: Optional[str] = None

    # Activity
    public_repos_count: int = 0
    own_repos_count: int = 0
    forked_repos_count: int = 0
    account_age_days: int = 0
    followers: int = 0
    last_active: Optional[str] = None

    # Technologies
    all_technologies: List[str] = Field(default_factory=list)
    all_repo_frameworks: List[str] = Field(default_factory=list)

    # Quality
    total_stars: int = 0
    real_repos_count: int = 0
    scored_repos: List[GitHubRepo] = Field(default_factory=list)

    # Verdict
    github_score: str = "INACTIVE"

    # Three-tier CV skills verification
    cv_skills_confirmed: List[str] = Field(default_factory=list)
    cv_skills_likely: List[str] = Field(default_factory=list)
    cv_skills_no_evidence: List[str] = Field(default_factory=list)

    # ── New: commit consistency (profile-level summary across top 3 repos) ────
    consistent_repos: List[str] = Field(default_factory=list)
    recently_active_repos: int = 0          # longest consecutive-week streak in any repo

    # ── New: code ownership depth ─────────────────────────────────────────────
    avg_ownership_ratio: float = 0.0    # avg % of commits authored by candidate across top 3

    # ── New: collaboration signals ────────────────────────────────────────────
    collaboration: CollaborationSignals = Field(default_factory=CollaborationSignals)


# ─────────────────────────────────────────────────────────────────────────────
# Evaluation models
# ─────────────────────────────────────────────────────────────────────────────

class EvidenceSignals(BaseModel):
    technical_evidence: str = "LOW"
    project_evidence: str = "LOW"
    leadership_evidence: str = "LOW"
    competition_evidence: str = "LOW"
    public_portfolio_evidence: str = "LOW"
    github_activity_evidence: str = "N/A"


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


# ─────────────────────────────────────────────────────────────────────────────
# Main CV analysis result
# ─────────────────────────────────────────────────────────────────────────────

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

    github_profile: Optional[GitHubProfile] = None

    raw_text_length: Optional[int] = None
    parsing_status: str = "SUCCESS"
    error_message: Optional[str] = None
    evaluation: Optional[CvEvaluation] = None