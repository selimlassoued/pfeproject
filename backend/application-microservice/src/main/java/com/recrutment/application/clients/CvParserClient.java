package com.recrutment.application.clients;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
public class CvParserClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${cv.parser.url:http://cv-parser-service:8085}")
    private String cvParserUrl;

    public CvParserClient(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
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