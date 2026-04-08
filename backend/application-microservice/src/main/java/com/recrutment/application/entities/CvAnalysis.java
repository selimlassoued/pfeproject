package com.recrutment.application.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CvAnalysis {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID applicationId;

    // Personal info
    private String candidateName;
    private String email;
    private String phone;

    @Column(columnDefinition = "text")
    private String location;

    @Column(columnDefinition = "text")
    private String desiredPosition;

    @Column(columnDefinition = "text")
    private String availability;

    @Column(columnDefinition = "text")
    private String summary;

    private String seniorityLevel;

    // Social links
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private SocialLinksEmbedded socialLinks;

    // Skills
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> skills;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> softSkills;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> certifications;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> awards;

    // Complex objects
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<LanguageEmbedded> languages;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<WorkExperienceEmbedded> workExperience;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<EducationEmbedded> education;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<HackathonEmbedded> hackathons;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<ProjectEmbedded> projects;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<VolunteerWorkEmbedded> volunteerWork;

    // Evaluation
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private CvEvaluationEmbedded evaluation;

    // GitHub profile — jsonb so new fields are stored automatically
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private GitHubProfileEmbedded githubProfile;

    // Stats
    private Float totalYearsExperience;
    private Integer rawTextLength;

    // Status
    private String parsingStatus;

    @Column(columnDefinition = "text")
    private String errorMessage;

    private Instant analyzedAt;

    // ── Embedded value objects ────────────────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SocialLinksEmbedded {
        private String linkedin;
        private String github;
        private String portfolio;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class LanguageEmbedded {
        private String name;
        private String level;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class WorkExperienceEmbedded {
        private String title;
        private String company;
        private String duration;
        private String description;
        private List<String> skillsUsed;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class EducationEmbedded {
        private String degree;
        private String institution;
        private String year;
        private String field;
        private String mention;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class HackathonEmbedded {
        private String title;
        private String rank;
        private String date;
        private String description;
        private List<String> skillsUsed;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ProjectEmbedded {
        private String title;
        private String description;
        private List<String> skillsUsed;
        private String url;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class VolunteerWorkEmbedded {
        private String role;
        private String organization;
        private String duration;
        private String description;
    }

    // ── GitHub embedded objects ───────────────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CommitActivityEmbedded {
        private List<Integer> weeklyCounts;
        private Integer activeWeeks;
        private Integer recentWeeksActive;
        private Integer longestStreak;
        private Boolean isConsistent;
        private Boolean recentlyActive;
        private Integer daysSincePush;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class GitHubRepoEmbedded {
        private String name;
        private String description;
        private String language;
        private List<String> allLanguages;
        private List<String> frameworks;
        private List<String> technologies;       // frameworks + non-implied languages
        private Integer stars;
        private String url;
        private Boolean isFork;
        private Integer sizeKb;
        private Integer commitCount;
        private Integer branchCount;
        private Integer daysOfActivity;
        private String lastPushed;
        private List<String> topics;
        private Integer score;
        private Boolean isReal;
        private List<String> scoreReasons;
        // ── New fields ──
        private Float ownershipRatio;            // candidate's commits / total commits
        private CommitActivityEmbedded commitActivity;
        private Integer complexityScore;         // 0–10
        private String complexityLabel;          // HIGH / MEDIUM / LOW
        private List<String> complexityReasons;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CollaborationEmbedded {
        private Integer activeForkCount;
        private List<String> collaboratedRepos;
        private Boolean hasCollaboration;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class GitHubProfileEmbedded {
        private String username;
        private String accountUrl;
        private String name;
        private String bio;
        private String location;
        private Integer publicReposCount;
        private Integer ownReposCount;
        private Integer forkedReposCount;
        private Integer accountAgeDays;
        private Integer followers;
        private String lastActive;
        // topLanguages removed — no longer in Python response
        private List<String> allTechnologies;    // frameworks + non-implied languages (clean)
        private List<String> allRepoFrameworks;  // all frameworks found across ALL repos (sorted)
        private Integer totalStars;
        private Integer realReposCount;
        private List<GitHubRepoEmbedded> scoredRepos;
        private String githubScore;              // STRONG / MODERATE / WEAK / INACTIVE / RATE_LIMITED
        // CV skills verification
        private List<String> cvSkillsConfirmed;
        private List<String> cvSkillsLikely;
        private List<String> cvSkillsNoEvidence;
        // ── New profile-level fields ──
        private List<String> consistentRepos;    // repo names where is_consistent=true
        private Integer recentlyActiveRepos;     // count of top repos pushed within last 6 months
        private Float avgOwnershipRatio;         // weighted by complexity_score across top 3 repos
        private CollaborationEmbedded collaboration;
    }

    // ── Evaluation embedded objects ───────────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class EvidenceSignalsEmbedded {
        private String technicalEvidence;
        private String projectEvidence;
        private String leadershipEvidence;
        private String competitionEvidence;
        private String publicPortfolioEvidence;
        private String githubActivityEvidence;   // HIGH / MEDIUM / LOW / N/A
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CvEvaluationEmbedded {
        private List<String> missingSections;
        private List<String> structureWarnings;
        private List<String> spellingWarnings;
        private List<String> dateWarnings;
        private List<String> gapWarnings;
        private List<String> profileStrengths;
        private List<String> profileWeaknesses;
        private List<String> recruiterInsights;
        private Integer likelyTyposCount;
        private Integer experienceGapCount;
        private Integer incompleteExperienceEntriesCount;
        private Integer incompleteEducationEntriesCount;
        private Boolean hasEmail;
        private Boolean hasPhone;
        private Boolean hasLinkedin;
        private Boolean hasGithub;
        private Boolean hasPortfolio;
        private Boolean hasProjects;
        private Boolean hasExperience;
        private Boolean hasEducation;
        private Boolean hasSkills;
        private Boolean hasLanguages;
        private EvidenceSignalsEmbedded evidenceSignals;
    }
}