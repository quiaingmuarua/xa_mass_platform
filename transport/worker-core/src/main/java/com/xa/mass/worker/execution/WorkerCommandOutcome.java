package com.xa.mass.worker.execution;

import java.util.Objects;

public final class WorkerCommandOutcome {

    private final String outcomeCode;
    private final String payload;

    private WorkerCommandOutcome(String outcomeCode, String payload) {
        if (outcomeCode == null || outcomeCode.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "outcomeCode must be non-blank"
            );
        }
        this.outcomeCode = outcomeCode;
        this.payload = Objects.requireNonNull(payload, "payload");
    }

    public static WorkerCommandOutcome of(
            String outcomeCode,
            String payload
    ) {
        return new WorkerCommandOutcome(outcomeCode, payload);
    }

    public String outcomeCode() {
        return outcomeCode;
    }

    public String payload() {
        return payload;
    }
}
