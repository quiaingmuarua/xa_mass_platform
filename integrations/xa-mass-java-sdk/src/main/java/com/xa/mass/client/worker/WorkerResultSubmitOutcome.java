package com.xa.mass.client.worker;

public record WorkerResultSubmitOutcome(
        String workerId,
        String taskId,
        String messageId,
        boolean submitted
) {
}
