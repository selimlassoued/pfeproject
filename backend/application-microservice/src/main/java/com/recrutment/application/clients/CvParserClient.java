package com.recrutment.application.clients;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recrutment.application.entities.CvAnalysis;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@Slf4j
public class CvParserClient {

    @Value("${cv.parser.url:http://localhost:8085}")
    private String cvParserUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public CvParserClient(@Qualifier("plainRestTemplate") RestTemplate restTemplate,
                          ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public CvAnalysis analyze(UUID applicationId, byte[] cvBytes, String filename) {
        try {
            log.info("[CvParserClient] Analyzing CV for application: {}", applicationId);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("application_id", applicationId.toString());
            body.add("filename", filename);

            ByteArrayResource fileResource = new ByteArrayResource(cvBytes) {
                @Override
                public String getFilename() { return filename; }
            };
            body.add("file", fileResource);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    cvParserUrl + "/api/cv-parser/analyze",
                    requestEntity,
                    String.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.info("[CvParserClient] Success for application: {}", applicationId);
                return mapToCvAnalysis(applicationId, response.getBody());
            } else {
                return buildFailedAnalysis(applicationId, "CV parser returned: " + response.getStatusCode());
            }

        } catch (Exception e) {
            log.error("[CvParserClient] Failed for application {}: {}", applicationId, e.getMessage());
            return buildFailedAnalysis(applicationId, e.getMessage());
        }
    }

    private CvAnalysis mapToCvAnalysis(UUID applicationId, String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            CvAnalysis a = new CvAnalysis();
            a.setApplicationId(applicationId);

            a.setCandidateName(getText(root, "candidate_name"));
            a.setEmail(getText(root, "email"));
            a.setPhone(getText(root, "phone"));
            a.setLocation(getText(root, "location"));
            a.setDesiredPosition(getText(root, "desired_position"));
            a.setAvailability(getText(root, "availability"));
            a.setSummary(getText(root, "summary"));
            a.setSeniorityLevel(getText(root, "seniority_level"));
            a.setParsingStatus(getText(root, "parsing_status"));
            a.setErrorMessage(getText(root, "error_message"));

            if (root.has("raw_text_length") && !root.get("raw_text_length").isNull())
                a.setRawTextLength(root.get("raw_text_length").asInt());
            if (root.has("total_years_experience") && !root.get("total_years_experience").isNull())
                a.setTotalYearsExperience(root.get("total_years_experience").asDouble());

            a.setSkills(getStringList(root, "skills"));
            a.setSoftSkills(getStringList(root, "soft_skills"));
            a.setCertifications(getStringList(root, "certifications"));
            a.setAwards(getStringList(root, "awards"));

            if (root.has("social_links") && !root.get("social_links").isNull()) {
                JsonNode sl = root.get("social_links");
                a.setSocialLinks(new CvAnalysis.SocialLinksEmbedded(
                        getText(sl, "linkedin"), getText(sl, "github"), getText(sl, "portfolio")));
            }

            if (root.has("languages") && root.get("languages").isArray()) {
                List<CvAnalysis.LanguageEmbedded> list = new ArrayList<>();
                for (JsonNode n : root.get("languages"))
                    list.add(new CvAnalysis.LanguageEmbedded(getText(n, "name"), getText(n, "level")));
                a.setLanguages(list);
            }

            if (root.has("work_experience") && root.get("work_experience").isArray()) {
                List<CvAnalysis.WorkExperienceEmbedded> list = new ArrayList<>();
                for (JsonNode n : root.get("work_experience"))
                    list.add(new CvAnalysis.WorkExperienceEmbedded(
                            getText(n, "title"), getText(n, "company"), getText(n, "duration"),
                            getText(n, "description"), getStringList(n, "skills_used")));
                a.setWorkExperience(list);
            }

            if (root.has("education") && root.get("education").isArray()) {
                List<CvAnalysis.EducationEmbedded> list = new ArrayList<>();
                for (JsonNode n : root.get("education"))
                    list.add(new CvAnalysis.EducationEmbedded(
                            getText(n, "degree"), getText(n, "institution"),
                            getText(n, "year"), getText(n, "field"), getText(n, "mention")));
                a.setEducation(list);
            }

            if (root.has("hackathons") && root.get("hackathons").isArray()) {
                List<CvAnalysis.HackathonEmbedded> list = new ArrayList<>();
                for (JsonNode n : root.get("hackathons"))
                    list.add(new CvAnalysis.HackathonEmbedded(
                            getText(n, "title"), getText(n, "rank"), getText(n, "date"),
                            getText(n, "description"), getStringList(n, "skills_used")));
                a.setHackathons(list);
            }

            if (root.has("projects") && root.get("projects").isArray()) {
                List<CvAnalysis.ProjectEmbedded> list = new ArrayList<>();
                for (JsonNode n : root.get("projects"))
                    list.add(new CvAnalysis.ProjectEmbedded(
                            getText(n, "title"), getText(n, "description"),
                            getStringList(n, "skills_used"), getText(n, "url")));
                a.setProjects(list);
            }

            if (root.has("volunteer_work") && root.get("volunteer_work").isArray()) {
                List<CvAnalysis.VolunteerWorkEmbedded> list = new ArrayList<>();
                for (JsonNode n : root.get("volunteer_work"))
                    list.add(new CvAnalysis.VolunteerWorkEmbedded(
                            getText(n, "role"), getText(n, "organization"),
                            getText(n, "duration"), getText(n, "description")));
                a.setVolunteerWork(list);
            }

            a.setAnalyzedAt(Instant.now());
            return a;

        } catch (Exception e) {
            log.error("[CvParserClient] Mapping failed: {}", e.getMessage());
            return buildFailedAnalysis(applicationId, "Mapping failed: " + e.getMessage());
        }
    }

    private String getText(JsonNode node, String field) {
        return (node.has(field) && !node.get(field).isNull()) ? node.get(field).asText() : null;
    }

    private List<String> getStringList(JsonNode node, String field) {
        List<String> result = new ArrayList<>();
        if (node.has(field) && node.get(field).isArray())
            for (JsonNode item : node.get(field))
                if (!item.isNull()) result.add(item.asText());
        return result;
    }

    private CvAnalysis buildFailedAnalysis(UUID applicationId, String error) {
        return CvAnalysis.builder()
                .applicationId(applicationId)
                .parsingStatus("FAILED")
                .errorMessage(error)
                .analyzedAt(Instant.now())
                .build();
    }
}