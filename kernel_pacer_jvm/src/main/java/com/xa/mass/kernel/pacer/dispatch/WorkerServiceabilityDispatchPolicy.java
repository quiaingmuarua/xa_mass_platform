package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreDelayTarget;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreObservation;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreState;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime.ProbeRequestOfferStatus;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

final class WorkerServiceabilityDispatchPolicy {

    private static final int PROBE_ROUND_LIMIT = 100;

    private final WorkerScoreCore workerScores;
    private final WorkerResourceCatalog workerCatalog;
    private final WorkerServiceabilityRuntime serviceability;
    private final LongSupplier currentTimeMillis;

    WorkerServiceabilityDispatchPolicy(
            WorkerScoreCore workerScores,
            WorkerResourceCatalog workerCatalog,
            WorkerServiceabilityRuntime serviceability
    ) {
        this(
                workerScores,
                workerCatalog,
                serviceability,
                System::currentTimeMillis
        );
    }

    WorkerServiceabilityDispatchPolicy(
            WorkerScoreCore workerScores,
            WorkerResourceCatalog workerCatalog,
            WorkerServiceabilityRuntime serviceability,
            LongSupplier currentTimeMillis
    ) {
        this.workerScores = Objects.requireNonNull(
                workerScores,
                "workerScores"
        );
        this.workerCatalog = Objects.requireNonNull(
                workerCatalog,
                "workerCatalog"
        );
        this.serviceability = Objects.requireNonNull(
                serviceability,
                "serviceability"
        );
        this.currentTimeMillis = Objects.requireNonNull(
                currentTimeMillis,
                "currentTimeMillis"
        );
    }

    int dispatchProbes(
            List<String> orderedWorkerGroupIds,
            WorkerServiceabilityDispatchConfig config
    ) {
        List<String> workerGroupIds = List.copyOf(Objects.requireNonNull(
                orderedWorkerGroupIds,
                "orderedWorkerGroupIds"
        ));
        Objects.requireNonNull(config, "config");
        if (workerGroupIds.isEmpty()) {
            return 0;
        }
        LinkedHashSet<String> uniqueGroupIds = new LinkedHashSet<>(
                workerGroupIds
        );
        if (uniqueGroupIds.size() != workerGroupIds.size()
                || uniqueGroupIds.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException(
                    "orderedWorkerGroupIds must contain unique non-empty IDs"
            );
        }

        long nowMillis = currentTimeMillis.getAsLong();
        long hotProbeCutoffMillis = hotProbeCutoffMillis(
                nowMillis,
                config.hotEligibilityFloorMillis(),
                config.probeRetryIntervalMillis()
        );
        Set<String> excludedEndpoints = Set.copyOf(
                config.probeExcludedEndpointManagerIds()
        );
        int remainingProbeBudget = PROBE_ROUND_LIMIT;
        int offered = 0;
        for (String workerGroupId : workerGroupIds) {
            if (remainingProbeBudget == 0) {
                break;
            }
            List<WorkerScoreObservation> hot = workerScores.acquireHotCandidatesBefore(
                    workerGroupId,
                    hotProbeCutoffMillis,
                    remainingProbeBudget
            );
            List<WorkerScoreObservation> candidates = hot.isEmpty()
                    ? workerScores.acquireRecoveryRecheckCandidates(
                            workerGroupId,
                            remainingProbeBudget
                    )
                    : hot;
            if (candidates.isEmpty()) {
                continue;
            }

            List<String> workerIds = candidates.stream()
                    .map(WorkerScoreObservation::workerId)
                    .toList();
            Map<String, WorkerScoreState> states =
                    workerScores.getScoreStates(workerGroupId, workerIds);
            Map<String, WorkerDescriptor> descriptors =
                    workerCatalog.getWorkerDescriptors(
                            workerIds
                    );
            LinkedHashMap<String, WorkerScoreDelayTarget> targets = new LinkedHashMap<>();
            LinkedHashMap<String, WorkerDescriptor> probeDescriptors =
                    new LinkedHashMap<>();
            for (WorkerScoreObservation candidate : candidates) {
                WorkerScoreState state = states.get(candidate.workerId());
                WorkerDescriptor descriptor = descriptors.get(
                        candidate.workerId()
                );
                if (state == null
                        || state.score() != candidate.score()
                        || descriptor == null
                        || !workerGroupId.equals(descriptor.workerGroupId())
                        || !candidate.workerId().equals(
                                descriptor.workerId()
                        )) {
                    continue;
                }
                if (excludedEndpoints.contains(
                        descriptor.endpointManagerId()
                )
                        || state.polarity()
                        == WorkerScorePolarity.RECOVERY_RECHECK
                        && state.laneRank()
                        >= config.maxRecoveryAttempts()) {
                    coldPark(
                            workerGroupId,
                            state,
                            config.maxRecoveryAttempts()
                    );
                    continue;
                }
                probeDescriptors.put(candidate.workerId(), descriptor);
                boolean isHot = state.polarity() == WorkerScorePolarity.HOT_ACQUIRE;
                long delayMillis = config.probeRetryIntervalMillis()
                        * (isHot ? 1L : state.laneRank() + 2L);
                targets.put(candidate.workerId(), new WorkerScoreDelayTarget(
                        candidate.score(), delayMillis, isHot ? 0 : state.laneRank() + 1
                ));
            }

            Map<String, WorkerScoreTransitionResult> results =
                    workerScores.deferObservedToRecovery(workerGroupId, targets);
            List<String> heldWorkerIds = new ArrayList<>();
            probeDescriptors.keySet().forEach(workerId -> {
                WorkerScoreTransitionResult result = results.get(workerId);
                if (result != null && result.status()
                        == WorkerScoreTransitionStatus.TRANSITIONED) {
                    heldWorkerIds.add(workerId);
                }
            });
            if (heldWorkerIds.size() > remainingProbeBudget) {
                throw new IllegalStateException(
                        "Serviceability hold exceeded the round budget"
                );
            }
            remainingProbeBudget -= heldWorkerIds.size();
            offered += offerProbes(heldWorkerIds, probeDescriptors);
        }
        return offered;
    }

    private void coldPark(
            String workerGroupId,
            WorkerScoreState worker,
            int maxRecoveryAttempts
    ) {
        if (worker.timeMillis() == WorkerScoreCore.PAUSE_TIME_MILLIS) {
            return;
        }
        long recoveryScore = worker.score();
        if (worker.polarity() == WorkerScorePolarity.HOT_ACQUIRE) {
            var toggled = workerScores.toggleCurrentPolarity(
                    workerGroupId,
                    worker.workerId(),
                    recoveryScore
            );
            if (toggled.status()
                    != WorkerScoreTransitionStatus.TRANSITIONED
                    || toggled.score() == null) {
                return;
            }
            recoveryScore = toggled.score();
        }
        workerScores.parkObservedRecoveryScore(
                workerGroupId,
                worker.workerId(),
                recoveryScore,
                maxRecoveryAttempts
        );
    }

    private int offerProbes(
            List<String> workerIds,
            Map<String, WorkerDescriptor> descriptors
    ) {
        Map<String, List<String>> workerIdsByAdapter = new LinkedHashMap<>();
        workerIds.forEach(workerId -> workerIdsByAdapter.computeIfAbsent(
                descriptors.get(workerId).endpointManagerId(),
                ignored -> new ArrayList<>()
        ).add(workerId));
        int offered = 0;
        for (Map.Entry<String, List<String>> adapter
                : workerIdsByAdapter.entrySet()) {
            Map<String, ProbeRequestOfferStatus> statuses =
                    serviceability.offerProbeRequests(
                            adapter.getKey(),
                            adapter.getValue()
                    );
            offered += (int) statuses.values().stream()
                    .filter(status -> status == ProbeRequestOfferStatus.OFFERED)
                    .count();
        }
        return offered;
    }

    private static long hotProbeCutoffMillis(
            long nowMillis,
            long hotEligibilityFloorMillis,
            long probeRetryIntervalMillis
    ) {
        long staleBeforeMillis = nowMillis <= probeRetryIntervalMillis
                ? 0
                : nowMillis - probeRetryIntervalMillis;
        long alignedStaleBeforeMillis = staleBeforeMillis
                / WorkerScoreCore.SLOT_MILLIS
                * WorkerScoreCore.SLOT_MILLIS;
        return Math.max(
                hotEligibilityFloorMillis,
                alignedStaleBeforeMillis
        );
    }

}
