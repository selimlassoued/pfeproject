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

    // Link to Application
    @Column(nullable = false, unique = true)
    private UUID applicationId;

    // Personal info
    private String candidateName;
    private String email;
    private String phone;
    private String location;
    private String desiredPosition;
    private String availability;
    private String summary;
    private String seniorityLevel;

    // Social links (stored as JSON)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private SocialLinksEmbedded socialLinks;

    // Skills (stored as JSON arrays)
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

    // Complex objects (stored as JSON)
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

    // Stats
    private Double totalYearsExperience;
    private Integer rawTextLength;

    // Status
    private String parsingStatus;
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
}