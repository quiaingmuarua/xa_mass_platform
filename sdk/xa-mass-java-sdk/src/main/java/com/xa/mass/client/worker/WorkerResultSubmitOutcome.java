package com.xa.mass.client.worker;

public record WorkerResultSubmitOutcome(
        String workerId,
        String resultCorrelationRef,
        boolean submitted
) {
}
