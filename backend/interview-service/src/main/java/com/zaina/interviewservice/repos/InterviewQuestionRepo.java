package com.zaina.interviewservice.repos;

import com.zaina.interviewservice.entities.InterviewQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InterviewQuestionRepo extends JpaRepository<InterviewQuestion, UUID> {
    List<InterviewQuestion> findByInterviewId(UUID interviewId);
    void deleteByInterviewId(UUID interviewId);
}