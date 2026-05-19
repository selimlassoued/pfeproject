from pydantic import BaseModel, Field
from typing import List, Optional, Dict, Literal,Any


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

    # Set True when account can't be assessed (NO_PUBLIC_WORK / RATE_LIMITED).
    # Signals downstream code to treat the profile as neutral (no penalty).
    verification_skipped: bool = False

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

    # ── New: per-tech usage stats (languages + frameworks) ───────────────────
    # Keyed by lowercase tech name. Each value: {repo_count, first_used, last_used, years}
    # Built from all own repos with size > 10 KB. Provides a real-evidence floor
    # for "years of experience per skill" used by the scoring system.
    tech_stats: Dict[str, Dict[str, int]] = Field(default_factory=dict)


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
class CareerInsights(BaseModel):
    job_hopping_flag: bool = Field(..., description="Alerte si le candidat a eu plus de 3 postes en 2 ans.")
    longest_tenure_months: Optional[int] = Field(None, description="Durée du poste le plus long en mois.")
    seniority_growth_summary: Optional[str] = Field(None, description="Résumé de la progression de carrière.")
    industry_loyalty_percentage: Optional[float] = Field(None, description="Pourcentage de fidélité à un secteur.")

class ExtracurricularInsights(BaseModel):
    hackathon_enthusiast: bool = Field(..., description="Indique si le candidat participe aux hackathons.")
    leadership_roles: List[str] = Field(default_factory=list, description="Liste des rôles de leadership (ex: IEEE).")
    community_impact: Optional[str] = Field(None, description="Impact communautaire détecté.")

class SkillEvidence(BaseModel):
    skill: str = Field(..., description="Compétence validée.")
    evidence_source: str = Field(..., description="Source (ex: LinkedIn Posts).")
    description: str = Field(..., description="Détails de la preuve.")
    confidence_level: Literal['High', 'Medium', 'Low'] = Field(..., description="Niveau de confiance.")

class EthicalAnalysis(BaseModel):
    status: Literal['SAFE', 'FLAGGED'] = Field(..., description="Statut éthique.")
    reason: Optional[str] = Field(None, description="Raison du statut.")
    activity_level: Literal['Low', 'High', 'UNKNOWN'] = Field(..., description="Niveau d'activité.")
    top_topics: List[str] = Field(default_factory=list, description="Sujets principaux.")

class LinkedInEnrichment(BaseModel):
    profile_url: Optional[str] = Field(None, description="URL LinkedIn.")
    headline: Optional[str] = Field(None, description="Titre LinkedIn.")
    about_section: Optional[str] = Field(None, description="Bio LinkedIn.")
    latest_posts: List[str] = Field(default_factory=list, description="Posts analysés.")
    certifications: List[str] = Field(default_factory=list, description="Certifications.")
    career_insights: Optional[CareerInsights] = Field(None, description="Insights de carrière.")
    extracurricular_insights: Optional[ExtracurricularInsights] = Field(None, description="Engagement extrascolaire.")
    skill_validation: List[SkillEvidence] = Field(default_factory=list, description="Preuves de compétences.")
    ethical_status: str = Field("SAFE", description="Compatibilité avec nlp_parser.py")
    ethical_analysis: Optional[EthicalAnalysis] = Field(None, description="Analyse éthique détaillée.")


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
    knowledge: List[str] = Field(default_factory=list)
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

    # LLM-derived per-skill classification: {skill_lower: {volatility:int, implies:list[str]}}.
    # Populated by skill_intel.get_skill_intelligence() after parsing. Used by the
    # scoring system to obtain per-skill half-life and framework→language implications
    # generically (no hardcoded framework lists). Cached across CVs.
    skill_intel: Dict[str, Dict[str, Any]] = Field(default_factory=dict)

    raw_text_length: Optional[int] = None
    parsing_status: str = "SUCCESS"
    error_message: Optional[str] = None
    evaluation: Optional[CvEvaluation] = None
    linkedin_enrichment: Optional[LinkedInEnrichment] = None


class JobRequirementInput(BaseModel):
    category: Optional[str] = None
    description: Optional[str] = None
    weight: Optional[float] = None
    min_years: Optional[int] = None
    max_years: Optional[int] = None
    skill_level: Optional[str] = None      # BASIC / INTERMEDIATE / ADVANCED
    degree_level: Optional[str] = None     # ANY / BAC / BTS_DUT / LICENCE_BACHELOR / MASTER / PHD
    enrollment_type: Optional[str] = None  # STUDENT / GRADUATE / BOTH
    language_level: Optional[str] = None   # A1 / A2 / B1 / B2 / C1 / C2


class ScoringWeights(BaseModel):
    skills: float = 0.40
    semantic: float = 0.35
    experience: float = 0.15
    seniority: float = 0.10


class SemanticMatchRequest(BaseModel):
    application_id: str
    job_title: Optional[str] = None
    job_description: Optional[str] = None
    requirements: List[JobRequirementInput] = Field(default_factory=list)
    cv_analysis: CvAnalysisResult
    scoring_weights: Optional[ScoringWeights] = None


class SemanticMatchResult(BaseModel):
    application_id: str
    job_fit_score: int = 0
    required_skills_matched: List[str] = Field(default_factory=list)
    required_skills_missing: List[str] = Field(default_factory=list)
    skill_scores: List[Dict[str, Any]] = Field(default_factory=list)  # per-skill scores
    experience_gap: float = 0.0
    seniority_match: bool = False
    embedding_score: int = 0
    requirement_scores: List[Dict[str, Any]] = Field(default_factory=list)
    strengths: List[str] = Field(default_factory=list)
    weaknesses: List[str] = Field(default_factory=list)
    recommendation: str = "REVIEW"
    interview_questions: List[str] = Field(default_factory=list)
    score_explanation: Optional[str] = None
