package com.zaina.interviewservice.messaging;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisRequestMessage implements Serializable {
    private UUID         interviewId;
    private String       jobTitle;
    private String       jobDescription;
    private List<String> jobRequirements;
    private String       candidateName;
    private List<String> candidateSkills;
    private String       candidateSummary;
    private String       githubScore;
    private  String recruiterJoinedAt;
    private String       candidateJoinedAt;
}