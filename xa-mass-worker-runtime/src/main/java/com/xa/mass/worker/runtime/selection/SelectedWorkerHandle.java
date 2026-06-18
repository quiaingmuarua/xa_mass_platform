package com.xa.mass.worker.runtime.selection;

import com.xa.mass.runtime.api.WorkerClaimTarget;

import java.util.Objects;
import java.util.UUID;

/**
 * Minimal selected worker handle returned to engine after worker-runtime has
 * evaluated worker facts and reserved capacity.
 */
public final class SelectedWorkerHandle {

    private final String workerId;
    private final String workerGroupId;
    private final String selectionScopeKey;
    private final String selectionToken;
    private final boolean exclusiveWorkerLock;
    private final SelectedWorkerClaimAuthorization claimAuthorization;

    SelectedWorkerHandle(String workerId,
                         String workerGroupId,
                         String selectionScopeKey,
                         boolean exclusiveWorkerLock,
                         SelectedWorkerClaimAuthorization claimAuthorization) {
        this(workerId, workerGroupId, selectionScopeKey, UUID.randomUUID().toString(),
                exclusiveWorkerLock, claimAuthorization);
    }

    public static SelectedWorkerHandle of(String workerId,
                                          String workerGroupId,
                                          String selectionScopeKey,
                                          boolean exclusiveWorkerLock) {
        return new SelectedWorkerHandle(workerId, workerGroupId, selectionScopeKey,
                exclusiveWorkerLock, SelectedWorkerClaimAuthorization.unrestricted());
    }

    private SelectedWorkerHandle(String workerId,
                                 String workerGroupId,
                                 String selectionScopeKey,
                                 String selectionToken,
                                 boolean exclusiveWorkerLock,
                                 SelectedWorkerClaimAuthorization claimAuthorization) {
        this.workerId = requireText(workerId, "workerId");
        this.workerGroupId = requireText(workerGroupId, "workerGroupId");
        this.selectionScopeKey = normalizeNullable(selectionScopeKey);
        this.selectionToken = requireText(selectionToken, "selectionToken");
        this.exclusiveWorkerLock = exclusiveWorkerLock;
        this.claimAuthorization = claimAuthorization == null
                ? SelectedWorkerClaimAuthorization.unrestricted()
                : claimAuthorization;
    }

    public String workerId() {
        return workerId;
    }

    public String workerGroupId() {
        return workerGroupId;
    }

    public String selectionToken() {
        return selectionToken;
    }

    public boolean exclusiveWorkerLock() {
        return exclusiveWorkerLock;
    }

    public WorkerClaimTarget toClaimTarget(String batchId, int capacity) {
        return claimAuthorization.toClaimTarget(workerGroupId, workerId, batchId, capacity);
    }

    String selectionScopeKey() {
        return selectionScopeKey;
    }

    SelectedWorkerEvidence toEvidence() {
        return new SelectedWorkerEvidence(workerId, workerGroupId, selectionScopeKey, selectionToken, exclusiveWorkerLock);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
