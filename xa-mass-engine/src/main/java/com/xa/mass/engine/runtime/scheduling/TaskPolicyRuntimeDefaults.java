package com.xa.mass.engine.runtime.scheduling;

import com.xa.mass.runtime.api.WorkEnqueueOptions;

/**
 * Runtime tunables used when resolving task policy presets.
 *
 * <p>These are resolver inputs, not defaults owned by the resolved policy value
 * object. Reading system properties here keeps behavior defaults in the preset
 * resolution owner.</p>
 */
public record TaskPolicyRuntimeDefaults(
        int interactivePerWorkerClaimLimit,
        long interactiveLeaseSeconds,
        long interactiveAssignmentRetryDelayMillis,
        long interactiveWorkRetryDelayMillis,
        long bulkWorkRetryDelayMillis,
        int interactiveMaxReadyItemsPerTask,
        int bulkMaxReadyItemsPerTask
) {

    public TaskPolicyRuntimeDefaults {
        interactivePerWorkerClaimLimit = Math.max(1, interactivePerWorkerClaimLimit);
        interactiveLeaseSeconds = Math.max(1L, interactiveLeaseSeconds);
        interactiveAssignmentRetryDelayMillis = Math.max(1L, interactiveAssignmentRetryDelayMillis);
        interactiveWorkRetryDelayMillis = Math.max(0L, interactiveWorkRetryDelayMillis);
        bulkWorkRetryDelayMillis = Math.max(0L, bulkWorkRetryDelayMillis);
        interactiveMaxReadyItemsPerTask = normalizeLimit(interactiveMaxReadyItemsPerTask);
        bulkMaxReadyItemsPerTask = normalizeLimit(bulkMaxReadyItemsPerTask);
    }

    public static TaskPolicyRuntimeDefaults fromSystemProperties() {
        return new TaskPolicyRuntimeDefaults(
                Integer.getInteger("xa.mass.engine.interactivePerWorkerClaimLimit", 1),
                Long.getLong("xa.mass.engine.interactiveLeaseSeconds", 30L),
                Long.getLong("xa.mass.engine.interactiveAssignmentRetryDelayMillis", 100L),
                Long.getLong("xa.mass.engine.interactiveWorkRetryDelayMillis", 100L),
                Long.getLong("xa.mass.engine.bulkWorkRetryDelayMillis", 0L),
                Integer.getInteger("xa.mass.engine.interactiveMaxReadyItemsPerTask", 10_000),
                Integer.getInteger("xa.mass.engine.bulkMaxReadyItemsPerTask", WorkEnqueueOptions.UNLIMITED)
        );
    }

    private static int normalizeLimit(int value) {
        return value <= 0 ? WorkEnqueueOptions.UNLIMITED : value;
    }
}
