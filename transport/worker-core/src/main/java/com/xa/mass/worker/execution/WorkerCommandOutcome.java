package com.xa.mass.worker.execution;

import com.xa.mass.worker.error.WorkerErrorCode;
import java.util.Objects;

public final class WorkerCommandOutcome {

    private final WorkerErrorCode errorCode;
    private final String payload;

    private WorkerCommandOutcome(WorkerErrorCode errorCode, String payload) {
        this.errorCode = errorCode;
        this.payload = Objects.requireNonNull(payload, "payload");
    }

    public static WorkerCommandOutcome succeeded(String payload) {
        return new WorkerCommandOutcome(null, payload);
    }

    public static WorkerCommandOutcome failed(
            WorkerErrorCode errorCode,
            String payload
    ) {
        return new WorkerCommandOutcome(Objects.requireNonNull(errorCode, "errorCode"), payload);
    }

    public boolean isSuccess() {
        return errorCode == null;
    }

    /** The local typed failure, or null for success; not a wire discriminator. */
    public WorkerErrorCode errorCode() {
        return errorCode;
    }

    public String diagnosticCode() {
        return errorCode == null ? "" : Integer.toString(errorCode.code());
    }

    public String payload() {
        return payload;
    }
}
