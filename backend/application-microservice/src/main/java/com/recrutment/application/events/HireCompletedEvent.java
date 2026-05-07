package com.recrutment.application.events;

import java.util.UUID;

public record HireCompletedEvent(
        UUID   jobId,
        String jobTitle,
        int    openings,
        String actorId
) {}
