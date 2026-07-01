package com.xa.mass.worker.runtime.selection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private final Long scoreBandClaimScore;
    private final boolean exclusiveWorkerLock;
    private final String eventBindingKey;
    private final String workerCandidateSource;
    private final String workerSchedulingResourceId;
    private final String workerSchedulingRoutingTags;
    private final Map<String, String> workerSchedulingAttributes;
    private final Boolean workerSchedulingMatchesRoutingCode;
    private final Double candidateScore;
    private final Integer workerActiveLeaseCount;
    private final Integer workerReservedCount;
    private final Integer workerDeclaredCapacity;
    private final Double workerEstimatedLoadRatio;

    SelectedWorkerHandle(String workerId,
                         String workerGroupId,
                         String selectionScopeKey,
                         boolean exclusiveWorkerLock) {
        this(workerId, workerGroupId, selectionScopeKey, UUID.randomUUID().toString(),
                null, exclusiveWorkerLock, null, null, null, null, Map.of(),
                null, null, null, null, null, null);
    }

    public static SelectedWorkerHandle of(String workerId,
                                          String workerGroupId,
                                          String selectionScopeKey,
                                          boolean exclusiveWorkerLock) {
        return new SelectedWorkerHandle(workerId, workerGroupId, selectionScopeKey,
                exclusiveWorkerLock);
    }

    private SelectedWorkerHandle(String workerId,
                                 String workerGroupId,
                                 String selectionScopeKey,
                                 String selectionToken,
                                 Long scoreBandClaimScore,
                                 boolean exclusiveWorkerLock,
                                 String eventBindingKey,
                                 String workerCandidateSource,
                                 String workerSchedulingResourceId,
                                 String workerSchedulingRoutingTags,
                                 Map<String, String> workerSchedulingAttributes,
                                 Boolean workerSchedulingMatchesRoutingCode,
                                 Double candidateScore,
                                 Integer workerActiveLeaseCount,
                                 Integer workerReservedCount,
                                 Integer workerDeclaredCapacity,
                                 Double workerEstimatedLoadRatio) {
        this.workerId = requireText(workerId, "workerId");
        this.workerGroupId = requireText(workerGroupId, "workerGroupId");
        this.selectionScopeKey = normalizeNullable(selectionScopeKey);
        this.selectionToken = requireText(selectionToken, "selectionToken");
        this.scoreBandClaimScore = scoreBandClaimScore;
        this.exclusiveWorkerLock = exclusiveWorkerLock;
        this.eventBindingKey = normalizeNullable(eventBindingKey);
        this.workerCandidateSource = normalizeNullable(workerCandidateSource);
        this.workerSchedulingResourceId = normalizeNullable(workerSchedulingResourceId);
        this.workerSchedulingRoutingTags = normalizeNullable(workerSchedulingRoutingTags);
        this.workerSchedulingAttributes = copyMap(workerSchedulingAttributes);
        this.workerSchedulingMatchesRoutingCode = workerSchedulingMatchesRoutingCode;
        this.candidateScore = candidateScore;
        this.workerActiveLeaseCount = workerActiveLeaseCount;
        this.workerReservedCount = workerReservedCount;
        this.workerDeclaredCapacity = workerDeclaredCapacity;
        this.workerEstimatedLoadRatio = workerEstimatedLoadRatio;
    }

    static SelectedWorkerHandle selectedWithEvidence(String workerId,
                                                     String workerGroupId,
                                                     String selectionScopeKey,
                                                     boolean exclusiveWorkerLock,
                                                     Long scoreBandClaimScore,
                                                     String eventBindingKey,
                                                     String workerCandidateSource,
                                                     String workerSchedulingResourceId,
                                                     String workerSchedulingRoutingTags,
                                                     Map<String, String> workerSchedulingAttributes,
                                                     Boolean workerSchedulingMatchesRoutingCode,
                                                     Double candidateScore,
                                                     Integer workerActiveLeaseCount,
                                                     Integer workerReservedCount,
                                                     Integer workerDeclaredCapacity,
                                                     Double workerEstimatedLoadRatio) {
        return new SelectedWorkerHandle(
                workerId,
                workerGroupId,
                selectionScopeKey,
                UUID.randomUUID().toString(),
                scoreBandClaimScore,
                exclusiveWorkerLock,
                eventBindingKey,
                workerCandidateSource,
                workerSchedulingResourceId,
                workerSchedulingRoutingTags,
                workerSchedulingAttributes,
                workerSchedulingMatchesRoutingCode,
                candidateScore,
                workerActiveLeaseCount,
                workerReservedCount,
                workerDeclaredCapacity,
                workerEstimatedLoadRatio
        );
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

    public Long scoreBandClaimScore() {
        return scoreBandClaimScore;
    }

    public boolean exclusiveWorkerLock() {
        return exclusiveWorkerLock;
    }

    String eventBindingKey() {
        return eventBindingKey;
    }

    String workerCandidateSource() {
        return workerCandidateSource;
    }

    String workerSchedulingResourceId() {
        return workerSchedulingResourceId;
    }

    String workerSchedulingRoutingTags() {
        return workerSchedulingRoutingTags;
    }

    Map<String, String> workerSchedulingAttributes() {
        return workerSchedulingAttributes;
    }

    Boolean workerSchedulingMatchesRoutingCode() {
        return workerSchedulingMatchesRoutingCode;
    }

    Double candidateScore() {
        return candidateScore;
    }

    Integer workerActiveLeaseCount() {
        return workerActiveLeaseCount;
    }

    Integer workerReservedCount() {
        return workerReservedCount;
    }

    Integer workerDeclaredCapacity() {
        return workerDeclaredCapacity;
    }

    Double workerEstimatedLoadRatio() {
        return workerEstimatedLoadRatio;
    }

    String selectionScopeKey() {
        return selectionScopeKey;
    }

    SelectedWorkerEvidence toEvidence() {
        return new SelectedWorkerEvidence(
                workerId,
                workerGroupId,
                selectionScopeKey,
                selectionToken,
                scoreBandClaimScore,
                exclusiveWorkerLock);
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

    private static Map<String, String> copyMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
