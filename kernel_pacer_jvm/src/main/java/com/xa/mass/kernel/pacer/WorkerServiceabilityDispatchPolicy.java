package com.xa.mass.kernel.pacer;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreObservation;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreState;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime
        .ProbeRequestOfferStatus;
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

    private final WorkerScoreCore workerScore;
    private final WorkerResourceCatalog workerCatalog;
    private final WorkerServiceabilityRuntime runtime;
    private final LongSupplier currentTimeMillis;
    private final Map<String, ProbeScoreSweep> hotSweeps =
            new LinkedHashMap<>();
    private final Map<String, ProbeScoreSweep> recoverySweeps =
            new LinkedHashMap<>();
    private int groupCursor;

    WorkerServiceabilityDispatchPolicy(
            WorkerScoreCore workerScore,
            WorkerResourceCatalog workerCatalog,
            WorkerServiceabilityRuntime runtime
    ) {
        this(
                workerScore,
                workerCatalog,
                runtime,
                System::currentTimeMillis
        );
    }

    WorkerServiceabilityDispatchPolicy(
            WorkerScoreCore workerScore,
            WorkerResourceCatalog workerCatalog,
            WorkerServiceabilityRuntime runtime,
            LongSupplier currentTimeMillis
    ) {
        this.workerScore = Objects.requireNonNull(
                workerScore,
                "workerScore"
        );
        this.workerCatalog = Objects.requireNonNull(
                workerCatalog,
                "workerCatalog"
        );
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.currentTimeMillis = Objects.requireNonNull(
                currentTimeMillis,
                "currentTimeMillis"
        );
    }

    int dispatchProbes(
            List<DueTaskObservation> tasks,
            WorkerServiceabilityDispatchConfig config,
            long hotEligibilityFloorMillis
    ) {
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(config, "config");
        WorkerServiceabilityAssemblyConfig.requireFloor(
                hotEligibilityFloorMillis
        );
        long nowMillis = currentTimeMillis.getAsLong();
        LinkedHashSet<String> groupIds = new LinkedHashSet<>();
        tasks.forEach(task -> groupIds.add(
                task.descriptor().workerGroupId()
        ));
        List<String> workerGroupIds = List.copyOf(groupIds);
        retainActiveGroupSweeps(workerGroupIds);
        if (workerGroupIds.isEmpty()) {
            groupCursor = 0;
            return 0;
        }

        String workerGroupId = workerGroupIds.get(
                groupCursor % workerGroupIds.size()
        );
        groupCursor = (groupCursor + 1) % workerGroupIds.size();
        List<WorkerScoreObservation> hot = hotPage(
                workerGroupId,
                hotEligibilityFloorMillis,
                nowMillis,
                config
        );
        List<WorkerScoreObservation> recovery = recoveryPage(
                workerGroupId,
                nowMillis,
                config
        );
        LinkedHashSet<String> candidateIds = new LinkedHashSet<>();
        hot.forEach(row -> candidateIds.add(row.workerId()));
        recovery.forEach(row -> candidateIds.add(row.workerId()));
        if (candidateIds.isEmpty()) {
            return 0;
        }

        List<String> candidates = List.copyOf(candidateIds);
        Map<String, WorkerScoreState> states = workerScore.getScoreStates(
                workerGroupId,
                candidates
        );
        List<String> eligibleIds = candidates.stream()
                .filter(workerId -> eligible(
                        states.get(workerId),
                        nowMillis,
                        hotEligibilityFloorMillis,
                        config.recoveryRetryIntervalMillis()
                ))
                .toList();
        if (eligibleIds.isEmpty()) {
            return 0;
        }

        Map<String, WorkerDescriptor> descriptors =
                workerCatalog.getWorkerDescriptors(
                        workerGroupId,
                        eligibleIds
                );
        Map<String, List<String>> workerIdsByAdapter = new LinkedHashMap<>();
        Set<String> excluded = Set.copyOf(
                config.probeExcludedEndpointManagerIds()
        );
        for (String workerId : eligibleIds) {
            WorkerDescriptor descriptor = descriptors.get(workerId);
            if (descriptor == null
                    || !workerGroupId.equals(descriptor.workerGroupId())
                    || !workerId.equals(descriptor.workerId())) {
                continue;
            }
            if (excluded.contains(descriptor.endpointManagerId())) {
                coldParkExcluded(
                        workerGroupId,
                        workerId,
                        states.get(workerId),
                        config.maxRecoveryAttempts()
                );
                continue;
            }
            workerIdsByAdapter.computeIfAbsent(
                    descriptor.endpointManagerId(),
                    ignored -> new ArrayList<>()
            ).add(workerId);
        }

        int offered = 0;
        for (Map.Entry<String, List<String>> adapter
                : workerIdsByAdapter.entrySet()) {
            Map<String, ProbeRequestOfferStatus> statuses =
                    runtime.offerProbeRequests(
                            adapter.getKey(),
                            adapter.getValue()
                    );
            offered += (int) statuses.values().stream()
                    .filter(status -> status == ProbeRequestOfferStatus.OFFERED)
                    .count();
        }
        return offered;
    }

    private void retainActiveGroupSweeps(List<String> workerGroupIds) {
        Set<String> retained = Set.copyOf(workerGroupIds);
        hotSweeps.keySet().removeIf(id -> !retained.contains(id));
        recoverySweeps.keySet().removeIf(id -> !retained.contains(id));
    }

    private List<WorkerScoreObservation> hotPage(
            String workerGroupId,
            long hotEligibilityFloorMillis,
            long nowMillis,
            WorkerServiceabilityDispatchConfig config
    ) {
        ProbeScoreSweep sweep = hotSweeps.computeIfAbsent(
                workerGroupId,
                ignored -> new ProbeScoreSweep()
        );
        if (nowMillis < sweep.resumeAtMillis) {
            return List.of();
        }
        List<WorkerScoreObservation> page =
                workerScore.acquirePreEpochHotCandidates(
                        workerGroupId,
                        hotEligibilityFloorMillis,
                        sweep.currentMaxWorkerScore,
                        config.hotScanLimit()
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
            long nowMillis,
            WorkerServiceabilityDispatchConfig config
    ) {
        ProbeScoreSweep sweep = recoverySweeps.computeIfAbsent(
                workerGroupId,
                ignored -> new ProbeScoreSweep()
        );
        if (nowMillis < sweep.resumeAtMillis) {
            return List.of();
        }
        List<WorkerScoreObservation> page =
                workerScore.acquireRecoveryRecheckCandidates(
                        workerGroupId,
                        sweep.currentMaxWorkerScore,
                        config.recoveryScanLimit()
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
            sweep.currentMaxWorkerScore = page.get(page.size() - 1).score();
            sweep.resumeAtMillis = 0;
            return;
        }
        sweep.currentMaxWorkerScore = WorkerScoreCore.ZERO_SCORE;
        sweep.resumeAtMillis = safeAdd(nowMillis, restartDelayMillis);
    }

    private static boolean eligible(
            WorkerScoreState state,
            long nowMillis,
            long hotEligibilityFloorMillis,
            long recoveryRetryIntervalMillis
    ) {
        if (state == null) {
            return false;
        }
        if (state.polarity() == WorkerScorePolarity.HOT_ACQUIRE) {
            return state.timeMillis() < hotEligibilityFloorMillis;
        }
        long multiplier = state.laneRank() + 1L;
        if (recoveryRetryIntervalMillis > Long.MAX_VALUE / multiplier) {
            return false;
        }
        long delay = multiplier * recoveryRetryIntervalMillis;
        return delay <= nowMillis
                && state.timeMillis() <= nowMillis - delay;
    }

    private void coldParkExcluded(
            String workerGroupId,
            String workerId,
            WorkerScoreState state,
            int maxRecoveryAttempts
    ) {
        if (state == null
                || state.timeMillis() == WorkerScoreCore.PAUSE_TIME_MILLIS) {
            return;
        }
        long observedScore = state.score();
        if (state.polarity() == WorkerScorePolarity.HOT_ACQUIRE) {
            var toggled = workerScore.toggleCurrentPolarity(
                    workerGroupId,
                    workerId,
                    observedScore
            );
            if (toggled.status()
                    != WorkerScoreTransitionStatus.TRANSITIONED
                    || toggled.score() == null) {
                return;
            }
            observedScore = toggled.score();
        }
        workerScore.exhaustRecoveryRecheck(
                workerGroupId,
                workerId,
                observedScore,
                maxRecoveryAttempts
        );
    }

    private static long safeAdd(long left, long right) {
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static final class ProbeScoreSweep {
        private long currentMaxWorkerScore = WorkerScoreCore.ZERO_SCORE;
        private long resumeAtMillis;
    }
}
