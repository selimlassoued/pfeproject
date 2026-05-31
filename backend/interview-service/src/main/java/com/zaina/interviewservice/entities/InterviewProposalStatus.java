package com.zaina.interviewservice.entities;

public enum InterviewProposalStatus {
    PENDING,
    CONFIRMED,
    DECLINED,    // candidate couldn't make any offered slot — recruiter re-proposes
    EXPIRED,
    CANCELLED
}
