package com.zaina.interviewservice.services;

import com.zaina.interviewservice.clients.ApplicationClient;
import com.zaina.interviewservice.clients.ApplicationCvClient;
import com.zaina.interviewservice.clients.JobClient;
import com.zaina.interviewservice.dto.*;
import com.zaina.interviewservice.entities.*;
import com.zaina.interviewservice.exceptions.ConflictException;
import com.zaina.interviewservice.repos.InterviewRepo;
import com.zaina.interviewservice.repos.InterviewResultRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import com.zaina.interviewservice.RabbitMQConfig;
import com.zaina.interviewservice.messaging.AnalysisRequestMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewService {


    private final InterviewRepo        interviewRepo;
    private final InterviewResultRepo  interviewResultRepo;
    private final ApplicationClient    applicationClient;
    private final ApplicationCvClient  applicationCvClient;
    private final JitsiTokenService    jitsiTokenService;
    private final RabbitTemplate       rabbitTemplate;
    private final JobClient jobClient;

    @Value("${recordings.dir:recordings}")
    private String recordingsDir;

    @Transactional
    public InterviewResponse scheduleInterview(ScheduleInterviewRequest request) {

        boolean hasActive = interviewRepo
                .findByApplicationId(request.getApplicationId())
                .stream()
                .anyMatch(i -> i.getStatus() == InterviewStatus.SCHEDULED
                        || i.getStatus() == InterviewStatus.IN_PROGRESS);

        if (hasActive) {
            throw new ConflictException(
                    "An active interview already exists for this application. " +
                            "Cancel or complete it before scheduling a new one."
            );
        }

        String roomName = "interview-" + request.getApplicationId();
        String roomUrl  = "https://meet.jit.si/" + roomName;

        Interview interview = Interview.builder()
                .applicationId(request.getApplicationId())
                .jobId(request.getJobId())
                .recruiterId(request.getRecruiterId())
                .candidateId(request.getCandidateId())
                .candidateEmail(request.getCandidateEmail())
                .recruiterEmail(request.getRecruiterEmail())
                .jobTitle(request.getJobTitle())
                .scheduledAt(request.getScheduledAt())
                .roomUrl(roomUrl)
                .roomName(roomName)
                .recordingConsent(Boolean.TRUE.equals(request.getRecordingConsent()))
                .status(InterviewStatus.SCHEDULED)
                .build();

        Interview saved = interviewRepo.save(interview);

        // ── Fetch CV context from application service ─────────────────────────
        try {
            ApplicationCvClient.CvSummary cv =
                    applicationCvClient.getCvSummary(request.getApplicationId());
            saved.setCandidateName(cv.candidateName());
            saved.setCandidateSkills(cv.skills());
            saved.setCandidateSummary(cv.summary());
            saved.setGithubScore(cv.githubScore());
            saved.setGithubFrameworks(cv.githubFrameworks());
            saved.setCvWeaknesses(cv.cvSkillsNoEvidence());
            interviewRepo.save(saved);
            log.info("CV context loaded for application {}", request.getApplicationId());
        } catch (Exception e) {
            log.warn("Could not fetch CV context for application {} — questions will be generic: {}",
                    request.getApplicationId(), e.getMessage());
        }

        interviewResultRepo.save(
                InterviewResult.builder()
                        .interview(saved)
                        .processingStatus(ProcessingStatus.PENDING)
                        .build()
        );
        try {
            JobClient.JobSummary job = jobClient.getJob(request.getJobId());
            saved.setJobDescription(job.description());
            saved.setJobRequirements(
                    job.requirements().stream()
                            .map(JobClient.RequirementSummary::description)
                            .toList()
            );
            interviewRepo.save(saved);
            log.info("Job context loaded for job {}", request.getJobId());
        } catch (Exception e) {
            log.warn("Could not fetch job context for {} — transcription prompt will be generic: {}",
                    request.getJobId(), e.getMessage());
        }

        try {
            applicationClient.updateStatus(
                    request.getApplicationId(),
                    new ApplicationStatusUpdateRequest("INTERVIEW_PHASE"));
            log.info("Application {} moved to INTERVIEW_PHASE", request.getApplicationId());
        } catch (Exception e) {
            log.error("Could not update application status for {}: {}", request.getApplicationId(), e.getMessage());
        }

        log.info("Interview {} scheduled for application {}", saved.getId(), saved.getApplicationId());
        return toResponse(saved);
    }

    public InterviewResponse updateConsent(UUID interviewId, ConsentUpdateRequest request) {
        Interview interview = findById(interviewId);
        if (interview.getStatus() == InterviewStatus.CANCELLED
                || interview.getStatus() == InterviewStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Consent cannot be updated for cancelled or completed interviews");
        }
        interview.setRecordingConsent(request.getRecordingConsent());
        return toResponse(interviewRepo.save(interview));
    }

    public InterviewResponse startInterview(UUID interviewId) {
        Interview interview = findById(interviewId);
        interview.setStatus(InterviewStatus.IN_PROGRESS);
        return toResponse(interviewRepo.save(interview));
    }

    @Transactional
    public InterviewResponse completeInterview(UUID interviewId) {
        Interview interview = findById(interviewId);
        interview.setStatus(InterviewStatus.COMPLETED);
        interview.setCompletedAt(LocalDateTime.now());
        return toResponse(interviewRepo.save(interview));
    }

    @Transactional
    public InterviewResponse cancelInterview(UUID interviewId) {
        Interview interview = findById(interviewId);
        interview.setStatus(InterviewStatus.CANCELLED);
        return toResponse(interviewRepo.save(interview));
    }

    public InterviewResponse getInterview(UUID interviewId) {
        return toResponse(findById(interviewId));
    }

    public List<InterviewResponse> getByApplication(UUID applicationId) {
        return interviewRepo.findByApplicationId(applicationId)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    public List<InterviewResponse> getByCandidate(UUID candidateId) {
        return interviewRepo.findByCandidateId(candidateId)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    public List<InterviewResponse> getByRecruiter(UUID recruiterId) {
        return interviewRepo.findByRecruiterId(recruiterId)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    private Interview findById(UUID id) {
        return interviewRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Interview not found: " + id));
    }

    private InterviewResponse toResponse(Interview i) {
        return InterviewResponse.builder()
                .id(i.getId())
                .applicationId(i.getApplicationId())
                .jobId(i.getJobId())
                .jobTitle(i.getJobTitle())
                .candidateEmail(i.getCandidateEmail())
                .recruiterEmail(i.getRecruiterEmail())
                .scheduledAt(i.getScheduledAt())
                .roomUrl(i.getRoomUrl())
                .recordingConsent(i.getRecordingConsent())
                .status(i.getStatus())
                .createdAt(i.getCreatedAt())
                .build();
    }

    public InterviewResponse saveRecording(UUID interviewId, MultipartFile file,
                                           String role, String joinedAt, String leftAt) {
        Interview interview = findById(interviewId);

        if (interview.getStatus() == InterviewStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot upload recording for a cancelled interview");
        }
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Audio file is empty");
        }

        Path dir = Path.of(recordingsDir).resolve(interviewId.toString());

        try {
            Files.createDirectories(dir);
            Path dest = dir.resolve(role + ".webm");
            file.transferTo(dest);

            if (joinedAt != null && leftAt != null) {
                Path meta = dir.resolve(role + "-meta.json");
                String json = String.format(
                        "{\"role\":\"%s\",\"joinedAt\":\"%s\",\"leftAt\":\"%s\"}",
                        role, joinedAt, leftAt);
                Files.writeString(meta, json);
            }
            log.info("Recording saved for interview {} role {}", interviewId, role);
        } catch (IOException e) {
            log.error("Failed to save recording for interview {}: {}", interviewId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to save recording");
        }

        InterviewResult result = interviewResultRepo.findByInterviewId(interviewId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "InterviewResult not found"));

        boolean bothExist = Files.exists(dir.resolve("recruiter.webm"))
                && Files.exists(dir.resolve("candidate.webm"));

        if (bothExist) {
            result.setProcessingStatus(ProcessingStatus.TRANSCRIBING);
            interviewResultRepo.save(result);
            log.info("Both recordings present for {} — publishing analysis job", interviewId);
            String recruiterJoinedAt = readMetaField(dir, "recruiter", "joinedAt");
            String candidateJoinedAt = readMetaField(dir, "candidate", "joinedAt");
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.ANALYSIS_EXCHANGE,
                    RabbitMQConfig.ANALYSIS_ROUTING,
                    new AnalysisRequestMessage(
                            interviewId,
                            interview.getJobTitle(),
                            interview.getJobDescription(),
                            interview.getJobRequirements(),
                            interview.getCandidateName(),
                            interview.getCandidateSkills(),
                            interview.getCandidateSummary(),
                            interview.getGithubScore(),
                            recruiterJoinedAt,
                            candidateJoinedAt
                    )
            );
        }

        return toResponse(interviewRepo.save(interview));
    }

    @Transactional
    public void handleParticipantLeft(UUID interviewId, String role) {
        if ("recruiter".equals(role)) {
            interviewRepo.markRecruiterLeft(interviewId);
        } else if ("candidate".equals(role)) {
            interviewRepo.markCandidateLeft(interviewId);
        }

        Interview interview = findById(interviewId);
        log.info("Interview {} — {} left (recruiterLeft={}, candidateLeft={})",
                interviewId, role, interview.getRecruiterLeft(), interview.getCandidateLeft());

        if (Boolean.TRUE.equals(interview.getRecruiterLeft())
                && Boolean.TRUE.equals(interview.getCandidateLeft())) {
            interview.setStatus(InterviewStatus.COMPLETED);
            interview.setCompletedAt(LocalDateTime.now());
            interviewRepo.save(interview);
            log.info("Interview {} marked COMPLETED — both participants left", interviewId);
        }
    }

    public Path getRecordingPath(UUID interviewId, String role) {
        return Path.of(recordingsDir).resolve(interviewId.toString()).resolve(role + ".webm");
    }

    public String getJitsiToken(UUID interviewId, String userId,
                                String displayName, String email, boolean moderator) {
        Interview interview = findById(interviewId);
        return jitsiTokenService.generateToken(
                interview.getRoomName(), userId, displayName, email, moderator);
    }
    private String readMetaField(Path dir, String role, String field) {
        try {
            Path meta = dir.resolve(role + "-meta.json");
            if (!Files.exists(meta)) return null;
            String json = Files.readString(meta);
            // Simple parse — no Jackson dependency needed for this flat structure
            String search = "\"" + field + "\":\"";
            int start = json.indexOf(search);
            if (start == -1) return null;
            start += search.length();
            int end = json.indexOf("\"", start);
            return end == -1 ? null : json.substring(start, end);
        } catch (Exception e) {
            log.warn("Could not read {}-meta.json field {}: {}", role, field, e.getMessage());
            return null;
        }
    }
}