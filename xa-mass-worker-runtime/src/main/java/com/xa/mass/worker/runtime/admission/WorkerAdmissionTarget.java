package com.xa.mass.worker.runtime.admission;

/**
 * Admission lifecycle target carried by the engine scheduling mainline.
 *
 * <p>The target is intentionally WorkerGroup-scoped. Engine scheduling already
 * has group evidence from candidate acquisition and runtime work records; it
 * must not depend on worker-id reverse lookup for reserve, confirm, release,
 * claim, or final accounting.</p>
 */
public record WorkerAdmissionTarget(String workerGroupId,
                                    String workerId,
                                    String taskId,
                                    int permits) {

    public WorkerAdmissionTarget {
        workerGroupId = requireText(workerGroupId, "workerGroupId");
        workerId = requireText(workerId, "workerId");
        taskId = requireText(taskId, "taskId");
        permits = Math.max(1, permits);
    }

    public static WorkerAdmissionTarget groupScoped(String workerGroupId, String workerId, String taskId) {
        return groupScoped(workerGroupId, workerId, taskId, 1);
    }

    public static WorkerAdmissionTarget groupScoped(String workerGroupId, String workerId, String taskId, int permits) {
        return new WorkerAdmissionTarget(requireText(workerGroupId, "workerGroupId"), workerId, taskId, permits);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

}
