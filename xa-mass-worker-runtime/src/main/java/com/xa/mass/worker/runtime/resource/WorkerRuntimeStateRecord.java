package com.xa.mass.worker.runtime.resource;

import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;
import com.xa.mass.worker.runtime.evidence.WorkerReadinessState;

import java.time.LocalDateTime;

/**
 * Current runtime state view for a worker.
 *
 * <p>This is not declaration-store truth. It is assembled from registry,
 * transport reachability, heartbeat freshness, dispatch gate, and admission
 * evidence at observation time.</p>
 */
public record WorkerRuntimeStateRecord(
        String workerId,
        String workerGroupId,
        String statusName,
        LocalDateTime lastHeartbeat,
        WorkerReachabilityState reachability,
        boolean dispatchEnabled,
        boolean removing,
        int capacityPermits,
        int reservedPermits,
        boolean exclusiveLeaseHeld,
        LocalDateTime observedAt
) {
    public WorkerRuntimeStateRecord {
        workerId = normalizeNullable(workerId);
        workerGroupId = normalizeNullable(workerGroupId);
        statusName = normalizeNullable(statusName);
        reachability = reachability == null ? WorkerReachabilityState.UNKNOWN : reachability;
        capacityPermits = Math.max(0, capacityPermits);
        reservedPermits = Math.max(0, reservedPermits);
    }

    public WorkerReadinessState readinessState() {
        return WorkerReadinessState.fromDispatchEvidence(dispatchEnabled, removing);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
