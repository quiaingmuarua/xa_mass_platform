package com.xa.mass.task.runtime;

public interface TaskRuntimeClaimPort {

    ClaimReadyOutcome claimReady(ClaimReadyCommand command);
}
