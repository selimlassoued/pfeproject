package com.recrutment.application.enums;

public enum ApplicationStatus {
    // ── Normal recruitment pipeline ───────────────────────────────────────────
    APPLIED,
    UNDER_REVIEW,
    INTERVIEW_PHASE,
    OFFER,
    HIRED,
    REJECTED,

    // ── Moderation ────────────────────────────────────────────────────────────
    FLAGGED,   // signaled by a recruiter — awaiting admin decision
    BLOCKED,   // confirmed by admin — out of consideration until unblocked

    // ── Candidate action ──────────────────────────────────────────────────────
    WITHDRAWN  // candidate withdrew — kept in DB, hidden from candidate dashboard
}