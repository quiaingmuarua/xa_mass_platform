package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreObservation;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreState;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime.ProbeRequestOfferStatus;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
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
    private final LinkedHashMap<String, GroupSweepState> groupSweeps =
            new LinkedHashMap<>();

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
            WorkerServiceabilityDispatchConfig config,
            long hotEligibilityFloorMillis
    ) {
        List<String> workerGroupIds = List.copyOf(Objects.requireNonNull(
                orderedWorkerGroupIds,
                "orderedWorkerGroupIds"
        ));
        Objects.requireNonNull(config, "config");
        WorkerServiceabilityDispatchAssemblyConfig.requireFloor(
                hotEligibilityFloorMillis
        );
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
                hotEligibilityFloorMillis,
                config.probeRetryIntervalMillis()
        );
        retainActiveGroupSweeps(workerGroupIds);
        Set<String> excludedEndpoints = Set.copyOf(
                config.probeExcludedEndpointManagerIds()
        );
        int remainingProbeBudget = PROBE_ROUND_LIMIT;
        int offered = 0;
        for (String workerGroupId : workerGroupIds) {
            if (remainingProbeBudget == 0) {
                break;
            }
            GroupSweepState sweepState = sweepState(workerGroupId);
            List<WorkerScoreObservation> hot = hotPage(
                    workerGroupId,
                    sweepState.hot,
                    hotProbeCutoffMillis,
                    nowMillis,
                    config,
                    remainingProbeBudget
            );
            List<WorkerScoreObservation> candidates = hot.isEmpty()
                    ? recoveryPage(
                            workerGroupId,
                            sweepState.recovery,
                            nowMillis,
                            config,
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
                            workerGroupId,
                            workerIds
                    );
            LinkedHashMap<String, Long> hotScores = new LinkedHashMap<>();
            LinkedHashMap<String, Long> recoveryScores =
                    new LinkedHashMap<>();
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
                        )
                        || !eligible(
                                state,
                                nowMillis,
                                hotProbeCutoffMillis,
                                config.probeRetryIntervalMillis()
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
                Map<String, Long> target = state.polarity()
                        == WorkerScorePolarity.HOT_ACQUIRE
                        ? hotScores : recoveryScores;
                target.put(candidate.workerId(), candidate.score());
            }

            Map<String, WorkerScoreTransitionResult> hotResults =
                    workerScores.holdObservedHotForServiceabilityProbes(
                            workerGroupId,
                            hotScores
                    );
            Map<String, WorkerScoreTransitionResult> recoveryResults =
                    workerScores.advanceObservedRecoveryRechecks(
                            workerGroupId,
                            recoveryScores
                    );
            List<String> heldWorkerIds = new ArrayList<>();
            probeDescriptors.keySet().forEach(workerId -> {
                WorkerScoreTransitionResult result = hotScores.containsKey(
                        workerId
                ) ? hotResults.get(workerId) : recoveryResults.get(workerId);
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

    private GroupSweepState sweepState(String workerGroupId) {
        return groupSweeps.computeIfAbsent(
                workerGroupId,
                ignored -> new GroupSweepState()
        );
    }

    private void retainActiveGroupSweeps(List<String> workerGroupIds) {
        Set<String> active = Set.copyOf(workerGroupIds);
        groupSweeps.keySet().removeIf(id -> !active.contains(id));
    }

    private List<WorkerScoreObservation> hotPage(
            String workerGroupId,
            ProbeScoreSweep sweep,
            long hotProbeCutoffMillis,
            long nowMillis,
            WorkerServiceabilityDispatchConfig config,
            int limit
    ) {
        if (nowMillis < sweep.resumeAtMillis) {
            return List.of();
        }
        List<WorkerScoreObservation> page =
                workerScores.acquireHotCandidatesBefore(
                        workerGroupId,
                        hotProbeCutoffMillis,
                        sweep.currentMaxWorkerScore,
                        limit
                );
        advanceSweep(
                sweep,
                page,
                nowMillis,
                config.probeSweepRestartDelayMillis()
        );
        return page;
    }

    private List<WorkerScoreObservation> recoveryPage(
            String workerGroupId,
            ProbeScoreSweep sweep,
            long nowMillis,
            WorkerServiceabilityDispatchConfig config,
            int limit
    ) {
        if (nowMillis < sweep.resumeAtMillis) {
            return List.of();
        }
        List<WorkerScoreObservation> page =
                workerScores.acquireRecoveryRecheckCandidates(
                        workerGroupId,
                        sweep.currentMaxWorkerScore,
                        limit
                );
        advanceSweep(
                sweep,
                page,
                nowMillis,
                config.probeSweepRestartDelayMillis()
        );
        return page;
    }

    private static void advanceSweep(
            ProbeScoreSweep sweep,
            List<WorkerScoreObservation> page,
            long nowMillis,
            long restartDelayMillis
    ) {
        if (!page.isEmpty()) {
            sweep.currentMaxWorkerScore = page.getLast().score();
            sweep.resumeAtMillis = 0;
            return;
        }
        sweep.currentMaxWorkerScore = 0;
        sweep.resumeAtMillis = safeAdd(nowMillis, restartDelayMillis);
    }

    private static boolean eligible(
            WorkerScoreState worker,
            long nowMillis,
            long hotProbeCutoffMillis,
            long probeRetryIntervalMillis
    ) {
        if (worker.polarity() == WorkerScorePolarity.HOT_ACQUIRE) {
            return worker.timeMillis() < hotProbeCutoffMillis;
        }
        long multiplier = worker.laneRank() + 1L;
        if (probeRetryIntervalMillis > Long.MAX_VALUE / multiplier) {
            return false;
        }
        long delay = multiplier * probeRetryIntervalMillis;
        return delay <= nowMillis
                && worker.timeMillis() <= nowMillis - delay;
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
        workerScores.exhaustRecoveryRecheck(
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

    private static long safeAdd(long left, long right) {
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
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

    private static final class GroupSweepState {
        private final ProbeScoreSweep hot = new ProbeScoreSweep();
        private final ProbeScoreSweep recovery = new ProbeScoreSweep();
    }

    private static final class ProbeScoreSweep {
        private long currentMaxWorkerScore;
        private long resumeAtMillis;
    }
}
