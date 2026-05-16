package com.xa.mass.base.runtime.result;

/**
 * Runtime-first result correlation snapshot for transport ingress.
 *
 * <p>The active lease is the authoritative execution truth. The projected
 * latest-attempt identity is a bounded compatibility hint that lets transport
 * ingress validate worker-reported attempt ids without requiring a full
 * attempt-history projection lookup on the hot path.</p>
 */
public record TaskResultCorrelation(String taskId,
                                    String messageId,
                                    boolean activeLeasePresent,
                                    String projectedAttemptId,
                                    String leaseToken,
                                    String workerId,
                                    String workerContextId,
                                    String batchId) {

    public static TaskResultCorrelation noActiveLease(String taskId, String messageId) {
        return new TaskResultCorrelation(taskId, messageId, false, null, null, null, null, null);
    }

    public static TaskResultCorrelation workerLevel(String taskId,
                                                    String messageId,
                                                    String projectedAttemptId,
                                                    String leaseToken,
                                                    String workerId,
                                                    String batchId) {
        return new TaskResultCorrelation(
                taskId,
                messageId,
                true,
                projectedAttemptId,
                leaseToken,
                workerId,
                null,
                batchId
        );
    }

    public static TaskResultCorrelation legacyContextBacked(String taskId,
                                                            String messageId,
                                                            String projectedAttemptId,
                                                            String leaseToken,
                                                            String workerId,
                                                            String workerContextId,
                                                            String batchId) {
        return new TaskResultCorrelation(
                taskId,
                messageId,
                true,
                projectedAttemptId,
                leaseToken,
                workerId,
                workerContextId,
                batchId
        );
    }

    public boolean workerLevelLease() {
        return activeLeasePresent && (workerContextId == null || workerContextId.isBlank());
    }
}
