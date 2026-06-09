package com.recrutment.application.clients;

import com.recrutment.application.clients.JobClient.JobDto;
import com.recrutment.application.clients.JobClient.JobRequirementDto;
import com.recrutment.application.dto.SemanticMatchDto;
import com.recrutment.application.entities.CvAnalysis;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
public class CvParserClient {

    private final RestTemplate restTemplate;

    @Value("${cv.parser.url:http://cv-parser-service:8085}")
    private String cvParserUrl;

    public CvParserClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Analyze a CV file.
     * CV parsing and GitHub enrichment run in parallel inside Python.
     * github_url is optional — enrichment runs for any candidate who provides one.
     */
    public CvAnalysis analyze(UUID applicationId, byte[] cvFile,
                              String filename, String githubUrl) {
        String url = cvParserUrl + "/api/cv-parser/analyze";
        log.info("[CvParserClient] Analyzing CV for application: {}", applicationId);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            ByteArrayResource fileResource = new ByteArrayResource(cvFile) {
                @Override
                public String getFilename() { return filename; }
            };

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("application_id", applicationId.toString());
            body.add("filename", filename);
            body.add("file", fileResource);

            if (githubUrl != null && !githubUrl.isBlank()) {
                body.add("github_url", githubUrl);
            }

            HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

            return mapToCvAnalysis(response.getBody(), applicationId);

        } catch (Exception e) {
            log.error("[CvParserClient] Analysis failed for {}: {}", applicationId, e.getMessage());
            CvAnalysis failed = new CvAnalysis();
            failed.setApplicationId(applicationId);
            failed.setParsingStatus("FAILED");
            failed.setErrorMessage("CV parsing failed: " + e.getMessage());
            return failed;
        }
    }

    public SemanticMatchDto match(UUID applicationId, CvAnalysis analysis, JobDto job) {
        String url = cvParserUrl + "/api/cv-parser/match";
        log.info("[CvParserClient] Matching CV to job for application: {}", applicationId);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> request = new HashMap<>();
            request.put("application_id", applicationId.toString());
            // job_id lets the matcher fetch the cached embedding for this job
            // from the job-microservice instead of re-embedding it via Ollama
            // every time a candidate applies.
            request.put("job_id", job != null && job.getId() != null ? job.getId().toString() : null);
            request.put("job_title", job != null ? job.getTitle() : null);
            request.put("job_description", job != null ? job.getDescription() : null);
            request.put("job_location", job != null ? job.getLocation() : null);
            request.put("work_arrangement", job != null ? job.getWorkArrangement() : null);
            // job_domain lets the matcher score the candidate's prior work
            // experience for industry-fit (e.g. banking → BIAT, Attijari).
            request.put("job_domain", job != null ? job.getDomain() : null);
            request.put("requirements", mapRequirements(job != null ? job.getRequirements() : List.of()));
            request.put("cv_analysis", mapCvAnalysis(analysis));
            if (job != null) {
                Map<String, Object> weights = new HashMap<>();
                weights.put("skills",     job.getSkillsWeight());
                weights.put("semantic",   job.getSemanticWeight());
                weights.put("experience", job.getExperienceWeight());
                weights.put("seniority",  job.getSeniorityWeight());
                request.put("scoring_weights", weights);
            }

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
            return mapToSemanticMatch(response.getBody());
        } catch (Exception e) {
            log.error("[CvParserClient] Semantic match failed for {}: {}", applicationId, e.getMessage());
            return new SemanticMatchDto(null, List.of(), List.of(),
                    List.<SemanticMatchDto.SkillScoreDto>of(), null, null,
                    null, List.<SemanticMatchDto.RequirementScoreDto>of(),
                    List.of(), List.of(), "REVIEW", List.of(), null,
                    List.<SemanticMatchDto.WarningDto>of(),
                    Boolean.FALSE, List.<String>of(),
                    null, List.<String>of());
        }
    }

    @SuppressWarnings("unchecked")
    private CvAnalysis mapToCvAnalysis(Map<?, ?> data, UUID applicationId) {
        if (data == null) {
            CvAnalysis empty = new CvAnalysis();
            empty.setApplicationId(applicationId);
            empty.setParsingStatus("FAILED");
            empty.setErrorMessage("Empty response from cv-parser-service");
            return empty;
        }

        try {
            CvAnalysis analysis = new CvAnalysis();
            analysis.setApplicationId(applicationId);
            Object statusObj = data.get("parsing_status");
            analysis.setParsingStatus(statusObj instanceof String s ? s : "SUCCESS");
            analysis.setErrorMessage((String) data.get("error_message"));
            analysis.setCandidateName((String) data.get("candidate_name"));
            analysis.setEmail((String) data.get("email"));
            analysis.setPhone((String) data.get("phone"));
            analysis.setLocation((String) data.get("location"));
            analysis.setSummary((String) data.get("summary"));
            analysis.setDesiredPosition((String) data.get("desired_position"));
            analysis.setAvailability((String) data.get("availability"));
            analysis.setSeniorityLevel((String) data.get("seniority_level"));
            analysis.setRawTextLength((Integer) data.get("raw_text_length"));
            analysis.setAnalyzedAt(java.time.Instant.now());

            Object totalYears = data.get("total_years_experience");
            if (totalYears instanceof Number) {
                analysis.setTotalYearsExperience(((Number) totalYears).floatValue());
            }

            // Social links
            Map<?, ?> socialRaw = (Map<?, ?>) data.get("social_links");
            if (socialRaw != null) {
                analysis.setSocialLinks(new CvAnalysis.SocialLinksEmbedded(
                        (String) socialRaw.get("linkedin"),
                        (String) socialRaw.get("github"),
                        (String) socialRaw.get("portfolio")
                ));
            }

            // Simple list fields
            analysis.setSkills(castList(data.get("skills")));
            analysis.setKnowledge(castList(data.get("knowledge")));
            analysis.setSoftSkills(castList(data.get("soft_skills")));
            analysis.setCertifications(castList(data.get("certifications")));
            analysis.setAwards(castList(data.get("awards")));

            // Languages
            List<?> langsRaw = (List<?>) data.get("languages");
            if (langsRaw != null) {
                analysis.setLanguages(langsRaw.stream()
                        .filter(l -> l instanceof Map)
                        .map(l -> {
                            Map<?, ?> m = (Map<?, ?>) l;
                            return new CvAnalysis.LanguageEmbedded(
                                    (String) m.get("name"),
                                    (String) m.get("level")
                            );
                        }).toList());
            }

            // Work experience
            List<?> expRaw = (List<?>) data.get("work_experience");
            if (expRaw != null) {
                analysis.setWorkExperience(expRaw.stream()
                        .filter(e -> e instanceof Map)
                        .map(e -> {
                            Map<?, ?> m = (Map<?, ?>) e;
                            return new CvAnalysis.WorkExperienceEmbedded(
                                    (String) m.get("title"),
                                    (String) m.get("company"),
                                    (String) m.get("duration"),
                                    (String) m.get("description"),
                                    castList(m.get("skills_used"))
                            );
                        }).toList());
            }

            // Education
            List<?> eduRaw = (List<?>) data.get("education");
            if (eduRaw != null) {
                analysis.setEducation(eduRaw.stream()
                        .filter(e -> e instanceof Map)
                        .map(e -> {
                            Map<?, ?> m = (Map<?, ?>) e;
                            return new CvAnalysis.EducationEmbedded(
                                    (String) m.get("degree"),
                                    (String) m.get("institution"),
                                    (String) m.get("year"),
                                    (String) m.get("field"),
                                    (String) m.get("mention")
                            );
                        }).toList());
            }

            // Hackathons
            List<?> hackRaw = (List<?>) data.get("hackathons");
            if (hackRaw != null) {
                analysis.setHackathons(hackRaw.stream()
                        .filter(h -> h instanceof Map)
                        .map(h -> {
                            Map<?, ?> m = (Map<?, ?>) h;
                            return new CvAnalysis.HackathonEmbedded(
                                    (String) m.get("title"),
                                    (String) m.get("rank"),
                                    (String) m.get("date"),
                                    (String) m.get("description"),
                                    castList(m.get("skills_used"))
                            );
                        }).toList());
            }

            // Projects
            List<?> projRaw = (List<?>) data.get("projects");
            if (projRaw != null) {
                analysis.setProjects(projRaw.stream()
                        .filter(p -> p instanceof Map)
                        .map(p -> {
                            Map<?, ?> m = (Map<?, ?>) p;
                            return new CvAnalysis.ProjectEmbedded(
                                    (String) m.get("title"),
                                    (String) m.get("description"),
                                    castList(m.get("skills_used")),
                                    (String) m.get("url")
                            );
                        }).toList());
            }

            // Volunteer work
            List<?> volRaw = (List<?>) data.get("volunteer_work");
            if (volRaw != null) {
                analysis.setVolunteerWork(volRaw.stream()
                        .filter(v -> v instanceof Map)
                        .map(v -> {
                            Map<?, ?> m = (Map<?, ?>) v;
                            return new CvAnalysis.VolunteerWorkEmbedded(
                                    (String) m.get("role"),
                                    (String) m.get("organization"),
                                    (String) m.get("duration"),
                                    (String) m.get("description")
                            );
                        }).toList());
            }

            // GitHub profile
            Map<?, ?> ghRaw = (Map<?, ?>) data.get("github_profile");
            if (ghRaw != null) {
                analysis.setGithubProfile(mapGitHubProfile(ghRaw));
            }

            // Evaluation
            Map<?, ?> evalRaw = (Map<?, ?>) data.get("evaluation");
            if (evalRaw != null) {
                analysis.setEvaluation(mapEvaluation(evalRaw));
            }

            return analysis;

        } catch (Exception e) {
            log.error("[CvParserClient] Mapping failed: {}", e.getMessage());
            CvAnalysis failed = new CvAnalysis();
            failed.setApplicationId(applicationId);
            failed.setParsingStatus("FAILED");
            failed.setErrorMessage("Mapping failed: " + e.getMessage());
            return failed;
        }
    }

    private SemanticMatchDto mapToSemanticMatch(Map<?, ?> data) {
        if (data == null) {
            return new SemanticMatchDto(null, List.of(), List.of(),
                    List.<SemanticMatchDto.SkillScoreDto>of(), null, null,
                    null, List.<SemanticMatchDto.RequirementScoreDto>of(),
                    List.of(), List.of(), "REVIEW", List.of(), null,
                    List.<SemanticMatchDto.WarningDto>of(),
                    Boolean.FALSE, List.<String>of(),
                    null, List.<String>of());
        }

        Integer score = toInt(data.get("job_fit_score"));
        Float gap = null;
        Object gapRaw = data.get("experience_gap");
        if (gapRaw instanceof Number number) {
            gap = number.floatValue();
        }

        return new SemanticMatchDto(
                score,                                                                      // jobFitScore
                castList(data.get("required_skills_matched")),                              // requiredSkillsMatched
                castList(data.get("required_skills_missing")),                              // requiredSkillsMissing
                mapSkillScores(data.get("skill_scores")),                                   // skillScores
                gap,                                                                        // experienceGap
                data.get("seniority_match") instanceof Boolean b ? b : null,               // seniorityMatch
                toInt(data.get("embedding_score")),                                         // embeddingScore
                mapRequirementScores(data.get("requirement_scores")),                       // requirementScores
                castList(data.get("strengths")),                                            // strengths
                castList(data.get("weaknesses")),                                           // weaknesses
                data.get("recommendation") instanceof String s ? s : "REVIEW",             // recommendation
                castList(data.get("interview_questions")),                                  // interviewQuestions
                data.get("score_explanation") instanceof String s ? s : null,              // scoreExplanation
                mapWarnings(data.get("warnings")),                                          // warnings
                data.get("must_have_failed") instanceof Boolean b ? b : Boolean.FALSE,     // mustHaveFailed
                castList(data.get("failed_must_haves")),                                    // failedMustHaves
                toInt(data.get("domain_fit_score")),                                        // domainFitScore
                castList(data.get("domain_match_evidence"))                                 // domainMatchEvidence
        );
    }

    @SuppressWarnings("unchecked")
    private List<SemanticMatchDto.WarningDto> mapWarnings(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(item -> item instanceof Map<?, ?>)
                .map(item -> {
                    Map<?, ?> m = (Map<?, ?>) item;
                    Map<String, Object> details = null;
                    if (m.get("details") instanceof Map<?, ?> dm) {
                        details = new HashMap<>();
                        for (Map.Entry<?, ?> e : dm.entrySet()) {
                            if (e.getKey() instanceof String k) details.put(k, e.getValue());
                        }
                    }
                    return new SemanticMatchDto.WarningDto(
                            m.get("kind")     instanceof String s ? s : null,
                            m.get("severity") instanceof String s ? s : "warning",
                            m.get("message")  instanceof String s ? s : null,
                            details
                    );
                })
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<SemanticMatchDto.SkillScoreDto> mapSkillScores(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(item -> item instanceof Map<?, ?>)
                .map(item -> {
                    Map<?, ?> m = (Map<?, ?>) item;
                    Boolean meets = m.get("meets_qualifier") instanceof Boolean b ? b : null;
                    return new SemanticMatchDto.SkillScoreDto(
                            m.get("skill")    instanceof String s ? s : null,
                            toInt(m.get("score")),
                            m.get("status")   instanceof String s ? s : "missing",
                            m.get("evidence") instanceof String e ? e : null,
                            m.get("reason")   instanceof String r ? r : null,
                            toInt(m.get("raw_score")),
                            m.get("qualifier") instanceof String s ? s : null,
                            toInt(m.get("qualifier_bar")),
                            meets,
                            toInt(m.get("gap_from_qualifier")),
                            m.get("signal") instanceof String s ? s : null
                    );
                })
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<SemanticMatchDto.RequirementScoreDto> mapRequirementScores(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(item -> item instanceof Map<?, ?>)
                .map(item -> {
                    Map<?, ?> m = (Map<?, ?>) item;
                    Float weight = null;
                    Object w = m.get("weight");
                    if (w instanceof Number n) weight = n.floatValue();
                    Boolean criticalGap     = m.get("critical_gap")     instanceof Boolean b ? b : null;
                    Boolean mustHave        = m.get("must_have")        instanceof Boolean b ? b : null;
                    Boolean mustHaveFailed  = m.get("must_have_failed") instanceof Boolean b ? b : null;
                    return new SemanticMatchDto.RequirementScoreDto(
                            m.get("category") instanceof String s ? s : null,
                            m.get("description") instanceof String s ? s : null,
                            toInt(m.get("score")),
                            weight,
                            m.get("evidence") instanceof String s ? s : null,
                            m.get("skill_level") instanceof String s ? s : null,
                            criticalGap,
                            mustHave,
                            mustHaveFailed
                    );
                })
                .toList();
    }

    private List<Map<String, Object>> mapRequirements(List<JobRequirementDto> requirements) {
        if (requirements == null) return List.of();
        return requirements.stream().map(req -> {
            Map<String, Object> mapped = new HashMap<>();
            // id flows through so the matcher can look up cached per-requirement
            // embeddings from job-microservice instead of re-embedding each time.
            mapped.put("id",              req.getId() != null ? req.getId().toString() : null);
            mapped.put("category",        req.getCategory());
            mapped.put("description",     req.getDescription());
            mapped.put("weight",          req.getWeight());
            mapped.put("min_years",       req.getMinYears());
            mapped.put("skill_level",     req.getSkillLevel());
            // skill_type tells the cv-parser matcher which catalog (HARD or
            // SOFT) to consult for this requirement. Null defaults to HARD on
            // the Python side for backward compatibility with legacy rows.
            mapped.put("skill_type",      req.getSkillType());
            mapped.put("degree_level",    req.getDegreeLevel());
            mapped.put("enrollment_type", req.getEnrollmentType());
            mapped.put("language_level",  req.getLanguageLevel());
            mapped.put("institute",       req.getInstitute());
            mapped.put("issuing_org",        req.getIssuingOrg());
            mapped.put("custom_issuing_org", req.getCustomIssuingOrg());
            mapped.put("require_current",    Boolean.TRUE.equals(req.getRequireCurrent()));
            mapped.put("validity_years",  req.getValidityYears());
            mapped.put("must_have",       Boolean.TRUE.equals(req.getMustHave()));
            return mapped;
        }).toList();
    }

    private Map<String, Object> mapCvAnalysis(CvAnalysis analysis) {
        Map<String, Object> mapped = new HashMap<>();
        mapped.put("application_id", analysis.getApplicationId() != null ? analysis.getApplicationId().toString() : null);
        mapped.put("candidate_name", analysis.getCandidateName());
        mapped.put("email", analysis.getEmail());
        mapped.put("phone", analysis.getPhone());
        mapped.put("location", analysis.getLocation());
        mapped.put("summary", analysis.getSummary());
        mapped.put("desired_position", analysis.getDesiredPosition());
        mapped.put("availability", analysis.getAvailability());
        mapped.put("skills", analysis.getSkills() != null ? analysis.getSkills() : List.of());
        mapped.put("soft_skills", analysis.getSoftSkills() != null ? analysis.getSoftSkills() : List.of());
        mapped.put("certifications", analysis.getCertifications() != null ? analysis.getCertifications() : List.of());
        mapped.put("awards", analysis.getAwards() != null ? analysis.getAwards() : List.of());
        mapped.put("total_years_experience", analysis.getTotalYearsExperience());
        mapped.put("seniority_level", analysis.getSeniorityLevel());
        mapped.put("parsing_status", analysis.getParsingStatus());
        mapped.put("error_message", analysis.getErrorMessage());
        mapped.put("work_experience", mapWorkExperience(analysis));
        mapped.put("education", mapEducation(analysis));
        mapped.put("languages", mapLanguages(analysis));
        mapped.put("projects", mapProjects(analysis));
        mapped.put("hackathons", mapHackathons(analysis));
        mapped.put("volunteer_work", mapVolunteerWork(analysis));
        mapped.put("social_links", mapSocialLinks(analysis));
        mapped.put("github_profile", mapGithubProfile(analysis));
        return mapped;
    }

    private List<Map<String, Object>> mapWorkExperience(CvAnalysis analysis) {
        if (analysis.getWorkExperience() == null) return List.of();
        return analysis.getWorkExperience().stream().map(exp -> {
            Map<String, Object> mapped = new HashMap<>();
            mapped.put("title", exp.getTitle());
            mapped.put("company", exp.getCompany());
            mapped.put("duration", exp.getDuration());
            mapped.put("description", exp.getDescription());
            mapped.put("skills_used", exp.getSkillsUsed() != null ? exp.getSkillsUsed() : List.of());
            return mapped;
        }).toList();
    }

    private List<Map<String, Object>> mapEducation(CvAnalysis analysis) {
        if (analysis.getEducation() == null) return List.of();
        return analysis.getEducation().stream().map(edu -> {
            Map<String, Object> mapped = new HashMap<>();
            mapped.put("degree", edu.getDegree());
            mapped.put("institution", edu.getInstitution());
            mapped.put("year", edu.getYear());
            mapped.put("field", edu.getField());
            mapped.put("mention", edu.getMention());
            return mapped;
        }).toList();
    }

    private List<Map<String, Object>> mapLanguages(CvAnalysis analysis) {
        if (analysis.getLanguages() == null) return List.of();
        return analysis.getLanguages().stream().map(lang -> {
            Map<String, Object> mapped = new HashMap<>();
            mapped.put("name", lang.getName());
            mapped.put("level", lang.getLevel());
            return mapped;
        }).toList();
    }

    private List<Map<String, Object>> mapProjects(CvAnalysis analysis) {
        if (analysis.getProjects() == null) return List.of();
        return analysis.getProjects().stream().map(project -> {
            Map<String, Object> mapped = new HashMap<>();
            mapped.put("title", project.getTitle());
            mapped.put("description", project.getDescription());
            mapped.put("skills_used", project.getSkillsUsed() != null ? project.getSkillsUsed() : List.of());
            mapped.put("url", project.getUrl());
            return mapped;
        }).toList();
    }

    private List<Map<String, Object>> mapHackathons(CvAnalysis analysis) {
        if (analysis.getHackathons() == null) return List.of();
        return analysis.getHackathons().stream().map(hackathon -> {
            Map<String, Object> mapped = new HashMap<>();
            mapped.put("title", hackathon.getTitle());
            mapped.put("rank", hackathon.getRank());
            mapped.put("date", hackathon.getDate());
            mapped.put("description", hackathon.getDescription());
            mapped.put("skills_used", hackathon.getSkillsUsed() != null ? hackathon.getSkillsUsed() : List.of());
            return mapped;
        }).toList();
    }

    private List<Map<String, Object>> mapVolunteerWork(CvAnalysis analysis) {
        if (analysis.getVolunteerWork() == null) return List.of();
        return analysis.getVolunteerWork().stream().map(volunteer -> {
            Map<String, Object> mapped = new HashMap<>();
            mapped.put("role", volunteer.getRole());
            mapped.put("organization", volunteer.getOrganization());
            mapped.put("duration", volunteer.getDuration());
            mapped.put("description", volunteer.getDescription());
            return mapped;
        }).toList();
    }

    private Map<String, Object> mapSocialLinks(CvAnalysis analysis) {
        if (analysis.getSocialLinks() == null) return null;
        Map<String, Object> mapped = new HashMap<>();
        mapped.put("linkedin", analysis.getSocialLinks().getLinkedin());
        mapped.put("github", analysis.getSocialLinks().getGithub());
        mapped.put("portfolio", analysis.getSocialLinks().getPortfolio());
        return mapped;
    }

    private Map<String, Object> mapGithubProfile(CvAnalysis analysis) {
        if (analysis.getGithubProfile() == null) return null;
        var gh = analysis.getGithubProfile();
        Map<String, Object> mapped = new HashMap<>();
        // Skill-evidence buckets the Python matcher already consumed:
        mapped.put("all_technologies", gh.getAllTechnologies() != null
                ? gh.getAllTechnologies() : List.of());
        mapped.put("all_repo_frameworks", gh.getAllRepoFrameworks() != null
                ? gh.getAllRepoFrameworks() : List.of());
        mapped.put("cv_skills_confirmed", gh.getCvSkillsConfirmed() != null
                ? gh.getCvSkillsConfirmed() : List.of());
        mapped.put("cv_skills_likely", gh.getCvSkillsLikely() != null
                ? gh.getCvSkillsLikely() : List.of());
        mapped.put("cv_skills_no_evidence", gh.getCvSkillsNoEvidence() != null
                ? gh.getCvSkillsNoEvidence() : List.of());
        // Activity signals the matcher needs to credit `git` automatically
        // when the candidate has a real GitHub profile (own_repos_count > 0
        // is the strongest "this person uses git daily" signal).
        mapped.put("username",          gh.getUsername());
        mapped.put("account_url",       gh.getAccountUrl());
        mapped.put("public_repos_count",gh.getPublicReposCount());
        mapped.put("own_repos_count",   gh.getOwnReposCount());
        mapped.put("forked_repos_count",gh.getForkedReposCount());
        mapped.put("real_repos_count",  gh.getRealReposCount());
        mapped.put("account_age_days",  gh.getAccountAgeDays());
        mapped.put("last_active",       gh.getLastActive());
        return mapped;
    }

    @SuppressWarnings("unchecked")
    private CvAnalysis.GitHubProfileEmbedded mapGitHubProfile(Map<?, ?> gh) {
        if (gh == null) return null;
        CvAnalysis.GitHubProfileEmbedded profile = new CvAnalysis.GitHubProfileEmbedded();
        profile.setUsername((String) gh.get("username"));
        profile.setAccountUrl((String) gh.get("account_url"));
        profile.setName((String) gh.get("name"));
        profile.setBio((String) gh.get("bio"));
        profile.setLocation((String) gh.get("location"));
        profile.setPublicReposCount(toInt(gh.get("public_repos_count")));
        profile.setOwnReposCount(toInt(gh.get("own_repos_count")));
        profile.setForkedReposCount(toInt(gh.get("forked_repos_count")));
        profile.setAccountAgeDays(toInt(gh.get("account_age_days")));
        profile.setFollowers(toInt(gh.get("followers")));
        profile.setLastActive((String) gh.get("last_active"));
        // top_languages removed — no longer in Python response
        profile.setAllTechnologies(castList(gh.get("all_technologies")));
        profile.setAllRepoFrameworks(castList(gh.get("all_repo_frameworks")));
        profile.setTotalStars(toInt(gh.get("total_stars")));
        profile.setRealReposCount(toInt(gh.get("real_repos_count")));
        profile.setGithubScore((String) gh.get("github_score"));
        // CV skills verification
        profile.setCvSkillsConfirmed(castList(gh.get("cv_skills_confirmed")));
        profile.setCvSkillsLikely(castList(gh.get("cv_skills_likely")));
        profile.setCvSkillsNoEvidence(castList(gh.get("cv_skills_no_evidence")));
        // New profile-level fields
        profile.setConsistentRepos(castList(gh.get("consistent_repos")));
        profile.setRecentlyActiveRepos(toInt(gh.get("recently_active_repos")));
        Object avgOwn = gh.get("avg_ownership_ratio");
        if (avgOwn instanceof Number) {
            profile.setAvgOwnershipRatio(((Number) avgOwn).floatValue());
        }

        // Collaboration
        Map<?, ?> collabRaw = (Map<?, ?>) gh.get("collaboration");
        if (collabRaw != null) {
            profile.setCollaboration(new CvAnalysis.CollaborationEmbedded(
                    toInt(collabRaw.get("active_forks_count")),
                    castList(collabRaw.get("collaborated_repos")),
                    (Boolean) collabRaw.get("has_collaboration")
            ));
        }

        // Scored repos
        List<?> reposRaw = (List<?>) gh.get("scored_repos");
        if (reposRaw != null) {
            profile.setScoredRepos(reposRaw.stream()
                    .filter(r -> r instanceof Map)
                    .map(r -> mapGitHubRepo((Map<?, ?>) r))
                    .toList());
        }
        return profile;
    }

    @SuppressWarnings("unchecked")
    private CvAnalysis.GitHubRepoEmbedded mapGitHubRepo(Map<?, ?> r) {
        CvAnalysis.GitHubRepoEmbedded repo = new CvAnalysis.GitHubRepoEmbedded();
        repo.setName((String) r.get("name"));
        repo.setDescription((String) r.get("description"));
        repo.setLanguage((String) r.get("language"));
        repo.setAllLanguages(castList(r.get("all_languages")));
        repo.setFrameworks(castList(r.get("frameworks")));
        repo.setTechnologies(castList(r.get("technologies")));
        repo.setStars(toInt(r.get("stars")));
        repo.setUrl((String) r.get("url"));
        repo.setIsFork((Boolean) r.get("is_fork"));
        repo.setSizeKb(toInt(r.get("size_kb")));
        repo.setCommitCount(toInt(r.get("commit_count")));
        repo.setBranchCount(toInt(r.get("branch_count")));
        repo.setDaysOfActivity(toInt(r.get("days_of_activity")));
        repo.setLastPushed((String) r.get("last_pushed"));
        repo.setTopics(castList(r.get("topics")));
        repo.setScore(toInt(r.get("score")));
        repo.setIsReal((Boolean) r.get("is_real"));
        repo.setScoreReasons(castList(r.get("score_reasons")));
        // New repo-level fields
        Object ownershipRatio = r.get("ownership_ratio");
        if (ownershipRatio instanceof Number) {
            repo.setOwnershipRatio(((Number) ownershipRatio).floatValue());
        }
        repo.setComplexityScore(toInt(r.get("complexity_score")));
        repo.setComplexityLabel((String) r.get("complexity_label"));
        repo.setComplexityReasons(castList(r.get("complexity_reasons")));

        // CommitActivity
        Map<?, ?> actRaw = (Map<?, ?>) r.get("commit_activity");
        if (actRaw != null) {
            CvAnalysis.CommitActivityEmbedded act = new CvAnalysis.CommitActivityEmbedded();
            act.setActiveWeeks(toInt(actRaw.get("active_weeks")));
            act.setRecentWeeksActive(toInt(actRaw.get("recent_weeks_active")));
            act.setLongestStreak(toInt(actRaw.get("longest_streak")));
            act.setIsConsistent((Boolean) actRaw.get("is_consistent"));
            act.setRecentlyActive((Boolean) actRaw.get("recently_active"));
            act.setDaysSincePush(toInt(actRaw.get("days_since_push")));
            // weekly_counts is List<Integer>
            Object wc = actRaw.get("weekly_counts");
            if (wc instanceof List<?> wcList) {
                act.setWeeklyCounts(wcList.stream()
                        .filter(v -> v instanceof Number)
                        .map(v -> ((Number) v).intValue())
                        .toList());
            }
            repo.setCommitActivity(act);
        }
        return repo;
    }

    @SuppressWarnings("unchecked")
    private CvAnalysis.CvEvaluationEmbedded mapEvaluation(Map<?, ?> ev) {
        CvAnalysis.CvEvaluationEmbedded eval = new CvAnalysis.CvEvaluationEmbedded();
        eval.setMissingSections(castList(ev.get("missing_sections")));
        eval.setStructureWarnings(castList(ev.get("structure_warnings")));
        eval.setSpellingWarnings(castList(ev.get("spelling_warnings")));
        eval.setDateWarnings(castList(ev.get("date_warnings")));
        eval.setGapWarnings(castList(ev.get("gap_warnings")));
        eval.setProfileStrengths(castList(ev.get("profile_strengths")));
        eval.setProfileWeaknesses(castList(ev.get("profile_weaknesses")));
        eval.setRecruiterInsights(castList(ev.get("recruiter_insights")));
        eval.setLikelyTyposCount(toInt(ev.get("likely_typos_count")));
        eval.setExperienceGapCount(toInt(ev.get("experience_gap_count")));
        eval.setIncompleteExperienceEntriesCount(toInt(ev.get("incomplete_experience_entries_count")));
        eval.setIncompleteEducationEntriesCount(toInt(ev.get("incomplete_education_entries_count")));
        eval.setHasEmail((Boolean) ev.get("has_email"));
        eval.setHasPhone((Boolean) ev.get("has_phone"));
        eval.setHasLinkedin((Boolean) ev.get("has_linkedin"));
        eval.setHasGithub((Boolean) ev.get("has_github"));
        eval.setHasPortfolio((Boolean) ev.get("has_portfolio"));
        eval.setHasProjects((Boolean) ev.get("has_projects"));
        eval.setHasExperience((Boolean) ev.get("has_experience"));
        eval.setHasEducation((Boolean) ev.get("has_education"));
        eval.setHasSkills((Boolean) ev.get("has_skills"));
        eval.setHasLanguages((Boolean) ev.get("has_languages"));

        Map<?, ?> sigRaw = (Map<?, ?>) ev.get("evidence_signals");
        if (sigRaw != null) {
            CvAnalysis.EvidenceSignalsEmbedded signals = new CvAnalysis.EvidenceSignalsEmbedded();
            signals.setTechnicalEvidence((String) sigRaw.get("technical_evidence"));
            signals.setProjectEvidence((String) sigRaw.get("project_evidence"));
            signals.setLeadershipEvidence((String) sigRaw.get("leadership_evidence"));
            signals.setCompetitionEvidence((String) sigRaw.get("competition_evidence"));
            signals.setPublicPortfolioEvidence((String) sigRaw.get("public_portfolio_evidence"));
            signals.setGithubActivityEvidence((String) sigRaw.get("github_activity_evidence"));
            eval.setEvidenceSignals(signals);
        }
        return eval;
    }

    @SuppressWarnings("unchecked")
    private CvAnalysis.LinkedInEnrichmentEmbedded mapLinkedInEnrichment(Map<?, ?> li) {
        if (li == null) return null;
        CvAnalysis.LinkedInEnrichmentEmbedded e = new CvAnalysis.LinkedInEnrichmentEmbedded();
        e.setProfileUrl((String) li.get("profile_url"));
        e.setHeadline((String) li.get("headline"));
        e.setEthicalStatus(li.get("ethical_status") instanceof String s ? s : "SAFE");

        // Ethical analysis details
        Map<?, ?> ethRaw = (Map<?, ?>) li.get("ethical_analysis");
        if (ethRaw != null) {
            e.setEthicalSummary((String) ethRaw.get("reason"));
            e.setActivityLevel(ethRaw.get("activity_level") instanceof String s ? s : "UNKNOWN");
            e.setTopTopics(castList(ethRaw.get("top_topics")));
            String level = e.getActivityLevel();
            e.setSocialScore("High".equalsIgnoreCase(level) ? 75 : "Low".equalsIgnoreCase(level) ? 30 : 50);
        } else {
            e.setActivityLevel("UNKNOWN");
            e.setSocialScore(50);
            e.setTopTopics(List.of());
        }

        // Career insights
        Map<?, ?> careerRaw = (Map<?, ?>) li.get("career_insights");
        if (careerRaw != null) {
            e.setJobHoppingFlag(careerRaw.get("job_hopping_flag") instanceof Boolean b ? b : false);
            e.setLongestTenureMonths(toInt(careerRaw.get("longest_tenure_months")));
            e.setSenioritySummary((String) careerRaw.get("seniority_growth_summary"));
        }

        // Extracurricular
        Map<?, ?> extraRaw = (Map<?, ?>) li.get("extracurricular_insights");
        if (extraRaw != null) {
            e.setHackathonEnthusiast(extraRaw.get("hackathon_enthusiast") instanceof Boolean b ? b : false);
            e.setLeadershipRoles(castList(extraRaw.get("leadership_roles")));
            e.setCommunityImpact((String) extraRaw.get("community_impact"));
        }

        // Skill validation
        List<?> svRaw = (List<?>) li.get("skill_validation");
        if (svRaw != null) {
            e.setSkillValidation(svRaw.stream()
                .filter(item -> item instanceof Map<?, ?>)
                .map(item -> {
                    Map<?, ?> m = (Map<?, ?>) item;
                    return new CvAnalysis.SkillValidationEmbedded(
                        (String) m.get("skill"),
                        (String) m.get("evidence_source"),
                        (String) m.get("description"),
                        (String) m.get("confidence_level")
                    );
                }).toList());
        }
        return e;
    }

    @SuppressWarnings("unchecked")
    private List<String> castList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item instanceof String)
                    .map(item -> (String) item)
                    .toList();
        }
        return List.of();
    }

    private Integer toInt(Object val) {
        if (val instanceof Number n) return n.intValue();
        return null;
    }
}