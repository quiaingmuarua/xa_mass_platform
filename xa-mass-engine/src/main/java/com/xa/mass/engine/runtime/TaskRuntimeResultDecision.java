package com.xa.mass.engine.runtime;

import com.xa.mass.task.runtime.MessageFinalityStatus;

public record TaskRuntimeResultDecision(
        MessageFinalityStatus status,
        boolean accepted,
        boolean progressDirty,
        boolean terminalCandidate,
        boolean retryScheduled,
        long retryAtMillis,
        String reason
) {

    public TaskRuntimeResultDecision {
        status = status == null ? MessageFinalityStatus.REJECTED : status;
        retryAtMillis = Math.max(0L, retryAtMillis);
        reason = reason == null ? "" : reason;
    }
}
