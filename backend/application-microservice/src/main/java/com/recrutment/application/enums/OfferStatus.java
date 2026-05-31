package com.recrutment.application.enums;

/**
 * Lifecycle of an offer attached to an Application.
 *
 *  SENT         — recruiter created and sent it; waiting on candidate
 *  NEGOTIATING  — at least one revision has been posted (by either side)
 *  ACCEPTED     — candidate accepted the current terms; Application moves to HIRED
 *  DECLINED     — candidate said no; offer terminates
 *  EXPIRED      — deadline passed without a decision; auto-set by the scheduler
 *  WITHDRAWN    — recruiter pulled the offer back
 */
public enum OfferStatus {
    SENT,
    NEGOTIATING,
    ACCEPTED,
    DECLINED,
    EXPIRED,
    WITHDRAWN
}
