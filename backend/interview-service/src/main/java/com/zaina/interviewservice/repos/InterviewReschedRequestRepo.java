package com.zaina.interviewservice.repos;

import com.zaina.interviewservice.entities.InterviewReschedRequest;
import com.zaina.interviewservice.entities.ReschedRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InterviewReschedRequestRepo extends JpaRepository<InterviewReschedRequest, UUID> {
    List<InterviewReschedRequest> findByInterviewId(UUID interviewId);
    List<InterviewReschedRequest> findByStatus(ReschedRequestStatus status);
}
