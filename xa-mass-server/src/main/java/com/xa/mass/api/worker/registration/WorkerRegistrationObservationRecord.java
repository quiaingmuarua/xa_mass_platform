package com.xa.mass.api.worker.registration;

import java.time.Instant;

public record WorkerRegistrationObservationRecord(
        String observationId,
        String resourceType,
        String resourceId,
        String action,
        String principalId,
        String principalType,
        String requestHash,
        String payloadJson,
        Instant occurredAt
) {
}
