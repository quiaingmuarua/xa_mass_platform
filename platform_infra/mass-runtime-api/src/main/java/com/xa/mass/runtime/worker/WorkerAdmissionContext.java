package com.xa.mass.runtime.worker;

public record WorkerAdmissionContext(
        String groupId,
        String workerId,
        String adapterNodeId,
        String taskId,
        int permits,
        long nowMillis
) {
}
