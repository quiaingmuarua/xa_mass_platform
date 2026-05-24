package com.xa.mass.engine.worker;

public record WorkerAdmissionContext(
        String groupId,
        String workerId,
        String adapterNodeId,
        String taskId,
        int permits,
        long nowMillis
) {
}
