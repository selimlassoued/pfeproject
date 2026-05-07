package com.zaina.interviewservice.repos;

import com.zaina.interviewservice.entities.InterviewResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface InterviewResultRepo extends JpaRepository<InterviewResult, UUID> {
    Optional<InterviewResult> findByInterviewId(UUID interviewId);
}
