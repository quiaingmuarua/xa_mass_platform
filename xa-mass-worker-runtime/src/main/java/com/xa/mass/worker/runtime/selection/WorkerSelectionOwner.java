package com.xa.mass.worker.runtime.selection;

import com.xa.mass.runtime.worker.slot.WorkerScoreBand;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandAcquireRequest;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlot;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlotMetadata;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlotRuntime;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandTransitionCommand;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandTransitionResult;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandTransitionStatus;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandTransitionType;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionRuntime;
import com.xa.mass.worker.runtime.candidate.WorkerCandidateRow;
import com.xa.mass.worker.runtime.evidence.WorkerGroupCapabilityView;
import com.xa.mass.worker.runtime.evidence.WorkerLoadSnapshot;
import com.xa.mass.worker.runtime.evidence.WorkerSchedulingViewRuntime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Worker-runtime owner for selected worker handles.
 */
public final class WorkerSelectionOwner implements WorkerSelectionRuntime {

    private static final int DEFAULT_STAGE_ONE_SAMPLE_MIN =
            Integer.getInteger("xa.mass.workerRuntime.selection.stageOneCandidateSampleMin", 512);
    private static final int DEFAULT_STAGE_ONE_SAMPLE_MAX =
            Integer.getInteger("xa.mass.workerRuntime.selection.stageOneCandidateSampleMax", 2_048);
    private static final int DEFAULT_STAGE_ONE_OVERSAMPLE_FACTOR =
            Integer.getInteger("xa.mass.workerRuntime.selection.stageOneCandidateOversampleFactor", 4);
    private static final long DEFAULT_SCORE_BAND_CLAIM_HOLD_MILLIS =
            Long.getLong("xa.mass.workerRuntime.selection.scoreBandClaimHoldMillis", 300_000L);

    private final WorkerSchedulingViewRuntime schedulingViewRuntime;
    private final WorkerAdmissionRuntime admissionRuntime;
    private final WorkerScoreBandSlotRuntime scoreBandSlotRuntime;

    public WorkerSelectionOwner(WorkerSchedulingViewRuntime schedulingViewRuntime,
                                WorkerAdmissionRuntime admissionRuntime,
                                WorkerScoreBandSlotRuntime scoreBandSlotRuntime) {
        this.schedulingViewRuntime = Objects.requireNonNull(schedulingViewRuntime, "schedulingViewRuntime");
        this.admissionRuntime = Objects.requireNonNull(admissionRuntime, "admissionRuntime");
        this.scoreBandSlotRuntime = Objects.requireNonNull(scoreBandSlotRuntime, "scoreBandSlotRuntime");
    }

    @Override
    public WorkerSelectionResult selectAndReserve(WorkerSelectionRequest request) {
        WorkerSelectionRequest resolvedRequest = request == null
                ? new WorkerSelectionRequest(null, null, 0, false)
                : request;
        if (resolvedRequest.requestedWorkerCount() <= 0) {
            return WorkerSelectionResult.empty(0);
        }

        WorkerSelectionIntent intent = resolvedRequest.intent();
        long nowMillis = System.currentTimeMillis();
        List<WorkerScoreBandSlot> scoreBandSlots = scoreBandSlotRuntime.acquire(
                acquireRequest(intent, resolvedRequest.requestedWorkerCount(), nowMillis)
        );
        List<CandidateEvaluation> accepted = new ArrayList<>();
        Map<String, Integer> rejectedByReason = new LinkedHashMap<>();
        Set<String> completedWorkerIds = new LinkedHashSet<>();

        if (scoreBandSlots.isEmpty()) {
            increment(rejectedByReason, "score-band acquire returned no eligible workers");
        }

        for (WorkerScoreBandSlot slot : scoreBandSlots) {
            WorkerCandidateRow row = candidateRow(slot);
            if (row == null || isBlank(row.workerId()) || !completedWorkerIds.add(row.workerId())) {
                increment(rejectedByReason, "duplicate or invalid worker candidate");
                continue;
            }
            CandidateEvaluation evaluation = evaluate(row, intent);
            if (evaluation.accepted()) {
                accepted.add(evaluation.withScoreBandClaimSource(slot));
            } else {
                increment(rejectedByReason, evaluation.reason());
            }
        }

        accepted.sort(Comparator.comparingDouble(CandidateEvaluation::score));

        List<SelectedWorkerHandle> selected = new ArrayList<>(resolvedRequest.requestedWorkerCount());
        for (CandidateEvaluation candidate : accepted) {
            if (selected.size() >= resolvedRequest.requestedWorkerCount()) {
                break;
            }
            if (resolvedRequest.exclusiveWorkerLock()
                    && !admissionRuntime.tryAcquireWorkerExclusiveLease(candidate.row().workerId())) {
                increment(rejectedByReason, "worker lock conflict");
                continue;
            }
            Long scoreBandClaimScore = claimScoreBandSlot(
                    candidate,
                    resolvedRequest.selectionScopeKey(),
                    nowMillis);
            if (scoreBandClaimScore == null) {
                if (resolvedRequest.exclusiveWorkerLock()) {
                    admissionRuntime.releaseWorkerExclusiveLease(candidate.row().workerId());
                }
                increment(rejectedByReason, "score-band claim rejected");
                continue;
            }
            selected.add(SelectedWorkerHandle.selectedWithEvidence(
                    candidate.row().workerId(),
                    candidate.row().workerGroupId(),
                    resolvedRequest.selectionScopeKey(),
                    resolvedRequest.exclusiveWorkerLock(),
                    scoreBandClaimScore,
                    eventBindingKey(intent),
                    workerCandidateSource(intent),
                    candidate.row().workerId(),
                    routingTagsCsv(candidate.row().attributes()),
                    candidate.row().attributes(),
                    matchesRoutingCode(candidate.row().attributes(), intent),
                    candidate.score(),
                    activeLeaseCount(candidate.load()),
                    reservedCountAfterSelection(candidate.load()),
                    declaredCapacity(candidate.load()),
                    estimatedLoadRatioAfterSelection(candidate.load())
            ));
        }

        int rejectedCount = rejectedByReason.values().stream().mapToInt(Integer::intValue).sum();
        return new WorkerSelectionResult(selected, resolvedRequest.requestedWorkerCount(), rejectedCount, rejectedByReason);
    }

    @Override
    public boolean confirmSelected(SelectedWorkerHandle handle) {
        return handle != null;
    }

    @Override
    public void releaseSelected(SelectedWorkerHandle handle) {
        if (handle != null) {
            releaseSelected(handle.toEvidence());
        }
    }

    @Override
    public void releaseSelected(SelectedWorkerEvidence evidence) {
        if (evidence == null) {
            return;
        }
        releaseSelectedLock(evidence);
        closeScoreBandClaim(evidence, "release");
    }

    @Override
    public void recordSelectedClaimed(SelectedWorkerHandle handle) {
        if (handle == null) {
            return;
        }
    }

    @Override
    public void recordSelectedFinal(SelectedWorkerEvidence evidence) {
        if (evidence == null) {
            return;
        }
        closeScoreBandClaim(evidence, "final");
    }

    @Override
    public void releaseSelectedLock(SelectedWorkerHandle handle) {
        if (handle != null) {
            releaseSelectedLock(handle.toEvidence());
        }
    }

    @Override
    public void releaseSelectedLock(SelectedWorkerEvidence evidence) {
        if (evidence == null || !evidence.exclusiveWorkerLock()) {
            return;
        }
        admissionRuntime.releaseWorkerExclusiveLease(evidence.workerId());
    }

    private CandidateEvaluation evaluate(WorkerCandidateRow row, WorkerSelectionIntent intent) {
        if (row == null || isBlank(row.workerId()) || isBlank(row.workerGroupId())) {
            return CandidateEvaluation.reject(row, null, null, "missing worker identity");
        }
        WorkerGroupCapabilityView groupView = schedulingViewRuntime.workerGroupReadView(row.workerGroupId()).orElse(null);
        if (groupView == null) {
            return CandidateEvaluation.reject(row, null, null, "worker group capability missing");
        }
        if (!supportsProject(groupView, intent.project())) {
            return CandidateEvaluation.reject(row, groupView, null, "worker project capability mismatch");
        }
        if (!supportsEvent(groupView, intent.eventCode())) {
            return CandidateEvaluation.reject(row, groupView, null, "worker event capability mismatch");
        }
        if (intent.targetWorkerId() != null && !intent.targetWorkerId().equals(row.workerId())) {
            return CandidateEvaluation.reject(row, groupView, null, "target worker mismatch");
        }
        if (!attributesMatch(row.attributes(), intent.targetWorkerAttributes())) {
            return CandidateEvaluation.reject(row, groupView, null, "target worker attributes mismatch");
        }
        if (!routeMatches(row.attributes(), intent)) {
            return CandidateEvaluation.reject(row, groupView, null, "routing code mismatch");
        }
        if (!schedulingViewRuntime.isWorkerDispatchEnabled(row.workerId())) {
            return CandidateEvaluation.reject(row, groupView, null, "worker dispatch disabled");
        }
        if (schedulingViewRuntime.hasWorkerExclusiveLease(row.workerId())) {
            return CandidateEvaluation.reject(row, groupView, null, "worker locked");
        }
        WorkerLoadSnapshot load = schedulingViewRuntime.getWorkerLoad(row.workerId());
        return CandidateEvaluation.accept(row, groupView, load, score(row, load, intent));
    }

    private static boolean supportsProject(WorkerGroupCapabilityView groupView, String project) {
        return project == null || groupView.projectCodes().contains(project);
    }

    private static boolean supportsEvent(WorkerGroupCapabilityView groupView, String eventCode) {
        return eventCode == null || groupView.eventCodes().contains(eventCode);
    }

    private static boolean attributesMatch(Map<String, String> workerAttributes, Map<String, String> requiredAttributes) {
        if (requiredAttributes == null || requiredAttributes.isEmpty()) {
            return true;
        }
        Map<String, String> attributes = workerAttributes == null ? Map.of() : workerAttributes;
        for (Map.Entry<String, String> entry : requiredAttributes.entrySet()) {
            if (!Objects.equals(attributes.get(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean routeMatches(Map<String, String> attributes, WorkerSelectionIntent intent) {
        if (intent == null || !intent.hasRoutingRequirement()) {
            return true;
        }
        if (!attributesMatch(attributes, intent.routeAttributes())) {
            return false;
        }
        String routingCode = intent.routingCode();
        if (routingCode == null) {
            return true;
        }
        return routingTags(attributes).contains(routingCode);
    }

    private static double score(WorkerCandidateRow row, WorkerLoadSnapshot load, WorkerSelectionIntent intent) {
        double loadRatio = load == null ? 1.0d : load.estimatedLoadRatio();
        double affinityScore = affinityScore(row.attributes(), intent == null ? null : intent.routingCode());
        return loadRatio + (1.0d - affinityScore);
    }

    private static double affinityScore(Map<String, String> attributes, String routingCode) {
        if (routingCode == null || routingCode.isBlank()) {
            return 1.0d;
        }
        Set<String> routingTags = routingTags(attributes);
        if (routingTags.contains(routingCode)) {
            return 1.0d;
        }
        String normalized = normalize(routingCode);
        if (attributes != null && attributes.values().stream().map(WorkerSelectionOwner::normalize).anyMatch(normalized::equals)) {
            return 1.0d;
        }
        if (routingTags.stream().map(WorkerSelectionOwner::normalize)
                .anyMatch(tag -> partialMatch(tag, normalized))) {
            return 0.5d;
        }
        return 0.0d;
    }

    private static Set<String> routingTags(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        addRoutingTags(tags, attributes.get("routingTag"));
        addRoutingTags(tags, attributes.get("routingTags"));
        return tags.isEmpty() ? Set.of() : Set.copyOf(tags);
    }

    private static void addRoutingTags(Set<String> tags, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String tag : value.split(",")) {
            String normalized = tag.trim();
            if (!normalized.isEmpty()) {
                tags.add(normalized);
            }
        }
    }

    private static String routingTagsCsv(Map<String, String> attributes) {
        Set<String> tags = routingTags(attributes);
        return tags.isEmpty() ? null : String.join(",", tags);
    }

    private static Boolean matchesRoutingCode(Map<String, String> attributes, WorkerSelectionIntent intent) {
        if (intent == null || intent.routingCode() == null) {
            return null;
        }
        return routingTags(attributes).contains(intent.routingCode());
    }

    private static String eventBindingKey(WorkerSelectionIntent intent) {
        if (intent == null || isBlank(intent.project()) || isBlank(intent.eventCode())) {
            return null;
        }
        return intent.project().trim() + ":" + intent.eventCode().trim();
    }

    private static String workerCandidateSource(WorkerSelectionIntent intent) {
        if (intent == null) {
            return null;
        }
        if (intent.targetWorkerId() != null) {
            return "TARGET_WORKER";
        }
        if (!intent.workerGroupIds().isEmpty()) {
            return "GROUP_SELECTOR";
        }
        return null;
    }

    private static int activeLeaseCount(WorkerLoadSnapshot load) {
        return load == null ? 0 : load.activeLeaseCount();
    }

    private static int reservedCountAfterSelection(WorkerLoadSnapshot load) {
        return load == null ? 1 : load.reservedCount() + 1;
    }

    private static int declaredCapacity(WorkerLoadSnapshot load) {
        return load == null ? 1 : load.declaredCapacity();
    }

    private static double estimatedLoadRatioAfterSelection(WorkerLoadSnapshot load) {
        return (activeLeaseCount(load) + reservedCountAfterSelection(load)) / (double) declaredCapacity(load);
    }

    private static WorkerScoreBandAcquireRequest acquireRequest(WorkerSelectionIntent intent,
                                                                int requestedWorkerCount,
                                                                long nowMillis) {
        if (intent == null || intent.workerGroupIds().isEmpty()) {
            return new WorkerScoreBandAcquireRequest(List.of(), null, 0, nowMillis);
        }
        if (intent.targetWorkerId() != null) {
            return WorkerScoreBandAcquireRequest.targetInHomeBuckets(
                    intent.workerGroupIds(),
                    intent.targetWorkerId(),
                    nowMillis);
        }
        return new WorkerScoreBandAcquireRequest(
                intent.workerGroupIds(),
                null,
                candidateAcquisitionLimit(intent, requestedWorkerCount),
                nowMillis);
    }

    private static int candidateAcquisitionLimit(WorkerSelectionIntent intent, int requestedWorkerCount) {
        if (intent != null && intent.targetWorkerId() != null) {
            return 1;
        }
        int sampleMin = Math.max(1, DEFAULT_STAGE_ONE_SAMPLE_MIN);
        int sampleMax = Math.max(sampleMin, DEFAULT_STAGE_ONE_SAMPLE_MAX);
        int oversampleFactor = Math.max(1, DEFAULT_STAGE_ONE_OVERSAMPLE_FACTOR);
        long desiredSample = Math.max(1L, (long) requestedWorkerCount * oversampleFactor);
        return (int) Math.min(sampleMax, Math.max(sampleMin, desiredSample));
    }

    private static WorkerCandidateRow candidateRow(WorkerScoreBandSlot slot) {
        if (slot == null || slot.metadata() == null) {
            return null;
        }
        WorkerScoreBandSlotMetadata metadata = slot.metadata();
        return new WorkerCandidateRow(
                metadata.workerId(),
                null,
                metadata.workerGroupId(),
                metadata.transportHint(),
                metadata.attributes());
    }

    private Long claimScoreBandSlot(CandidateEvaluation candidate,
                                    String selectionScopeKey,
                                    long nowMillis) {
        if (candidate == null || candidate.scoreBandSlot() == null) {
            return null;
        }
        WorkerScoreBandSlot slot = candidate.scoreBandSlot();
        long holdMillis = Math.max(1L, DEFAULT_SCORE_BAND_CLAIM_HOLD_MILLIS);
        long futureUntil = Math.max(nowMillis + holdMillis, WorkerScoreBand.TIME_SCORE_FLOOR);
        long futureScore = WorkerScoreBand.futureScore(futureUntil);
        WorkerScoreBandTransitionResult result = scoreBandSlotRuntime.transition(
                new WorkerScoreBandTransitionCommand(
                        WorkerScoreBandTransitionType.FUTURE_INTERVAL,
                        slot.homeBucketId(),
                        slot.workerId(),
                        futureScore,
                        slot.score(),
                        selectionScopeKey,
                        "SELECT",
                        "WORKER_RUNTIME",
                        nowMillis)
        );
        if (result == null || result.status() != WorkerScoreBandTransitionStatus.ACCEPTED || result.after() == null) {
            return null;
        }
        return result.after().score();
    }

    private void closeScoreBandClaim(SelectedWorkerEvidence evidence, String reasonCode) {
        if (evidence == null
                || evidence.scoreBandClaimScore() == null
                || isBlank(evidence.workerId())
                || isBlank(evidence.workerGroupId())) {
            return;
        }
        long nowMillis = System.currentTimeMillis();
        scoreBandSlotRuntime.transition(WorkerScoreBandTransitionCommand.claimClose(
                evidence.workerGroupId(),
                evidence.workerId(),
                evidence.scoreBandClaimScore(),
                WorkerScoreBand.eligibleScore(nowMillis),
                reasonCode,
                nowMillis));
    }

    private static void increment(Map<String, Integer> counts, String reason) {
        counts.merge(isBlank(reason) ? "worker rejected" : reason, 1, Integer::sum);
    }

    private static boolean partialMatch(String left, String right) {
        if (isBlank(left) || isBlank(right)) {
            return false;
        }
        return left.contains(right) || right.contains(left);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record CandidateEvaluation(WorkerCandidateRow row,
                                       WorkerGroupCapabilityView groupView,
                                       WorkerLoadSnapshot load,
                                       boolean accepted,
                                       String reason,
                                       double score,
                                       WorkerScoreBandSlot scoreBandSlot) {

        static CandidateEvaluation accept(WorkerCandidateRow row,
                                          WorkerGroupCapabilityView groupView,
                                          WorkerLoadSnapshot load,
                                          double score) {
            return new CandidateEvaluation(row, groupView, load, true, null, score, null);
        }

        static CandidateEvaluation reject(WorkerCandidateRow row,
                                          WorkerGroupCapabilityView groupView,
                                          WorkerLoadSnapshot load,
                                          String reason) {
            return new CandidateEvaluation(row, groupView, load, false, reason, Double.POSITIVE_INFINITY, null);
        }

        CandidateEvaluation withScoreBandClaimSource(WorkerScoreBandSlot slot) {
            return new CandidateEvaluation(row, groupView, load, accepted, reason, score, slot);
        }
    }
}
