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
}
