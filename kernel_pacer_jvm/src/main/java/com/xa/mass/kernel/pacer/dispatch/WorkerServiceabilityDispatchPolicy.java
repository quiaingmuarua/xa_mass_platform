package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.pacer.dispatch.WorkerServiceabilityDispatchMechanism.ServiceabilityPolarity;
import com.xa.mass.kernel.pacer.dispatch.WorkerServiceabilityDispatchMechanism.WorkerServiceabilityObservation;
import com.xa.mass.kernel.pacer.dispatch.WorkerServiceabilityDispatchMechanism.WorkerSweepPage;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime.ProbeRequestOfferStatus;
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

    private final WorkerServiceabilityDispatchMechanism mechanism;
    private final WorkerServiceabilityRuntime serviceability;
    private final LongSupplier currentTimeMillis;
    private final LinkedHashMap<String, GroupSweepState> groupSweeps =
            new LinkedHashMap<>();

    WorkerServiceabilityDispatchPolicy(
            WorkerServiceabilityDispatchMechanism mechanism,
            WorkerServiceabilityRuntime serviceability
    ) {
        this(mechanism, serviceability, System::currentTimeMillis);
    }

    WorkerServiceabilityDispatchPolicy(
            WorkerServiceabilityDispatchMechanism mechanism,
            WorkerServiceabilityRuntime serviceability,
            LongSupplier currentTimeMillis
    ) {
        this.mechanism = Objects.requireNonNull(mechanism, "mechanism");
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
        long nowMillis = currentTimeMillis.getAsLong();
        LinkedHashSet<String> uniqueGroupIds = new LinkedHashSet<>(
                workerGroupIds
        );
        if (uniqueGroupIds.size() != workerGroupIds.size()
                || uniqueGroupIds.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException(
                    "orderedWorkerGroupIds must contain unique non-empty IDs"
            );
        }
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
            List<WorkerCandidateReference> hot = hotPage(
                    workerGroupId,
                    sweepState.hot,
                    hotEligibilityFloorMillis,
                    nowMillis,
                    config,
                    remainingProbeBudget
            );
            List<WorkerCandidateReference> candidates = hot.isEmpty()
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

            List<WorkerServiceabilityObservation> rechecked =
                    mechanism.recheck(workerGroupId, candidates);
            List<WorkerServiceabilityObservation> excluded =
                    new ArrayList<>();
            List<WorkerServiceabilityObservation> exhausted =
                    new ArrayList<>();
            List<WorkerServiceabilityObservation> probe = new ArrayList<>();
            for (WorkerServiceabilityObservation worker : rechecked) {
                if (!eligible(
                        worker,
                        nowMillis,
                        hotEligibilityFloorMillis,
                        config.recoveryRetryIntervalMillis()
                )) {
                    continue;
                }
                if (excludedEndpoints.contains(worker.endpointManagerId())) {
                    excluded.add(worker);
                } else if (worker.polarity()
                        == ServiceabilityPolarity.RECOVERY
                        && worker.laneRank()
                        >= config.maxRecoveryAttempts()) {
                    exhausted.add(worker);
                } else {
                    probe.add(worker);
                }
            }
            List<WorkerServiceabilityObservation> cold = new ArrayList<>(
                    excluded.size() + exhausted.size()
            );
            cold.addAll(excluded);
            cold.addAll(exhausted);
            mechanism.coldPark(cold, config.maxRecoveryAttempts());

            List<WorkerServiceabilityObservation> held =
                    mechanism.holdForProbe(probe);
            if (held.size() > remainingProbeBudget) {
                throw new IllegalStateException(
                        "Serviceability hold exceeded the round budget"
                );
            }
            remainingProbeBudget -= held.size();
            offered += offerProbes(held);
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

    private List<WorkerCandidateReference> hotPage(
            String workerGroupId,
            ProbeScoreSweep sweep,
            long hotEligibilityFloorMillis,
            long nowMillis,
            WorkerServiceabilityDispatchConfig config,
            int limit
    ) {
        if (nowMillis < sweep.resumeAtMillis) {
            return List.of();
        }
        WorkerSweepPage page = mechanism.observePreEpochHot(
                workerGroupId,
                hotEligibilityFloorMillis,
                sweep.cursor,
                limit
        );
        advanceSweep(
                sweep,
                page,
                nowMillis,
                config.probeSweepRestartDelayMillis()
        );
        return page.candidates();
    }

    private List<WorkerCandidateReference> recoveryPage(
            String workerGroupId,
            ProbeScoreSweep sweep,
            long nowMillis,
            WorkerServiceabilityDispatchConfig config,
            int limit
    ) {
        if (nowMillis < sweep.resumeAtMillis) {
            return List.of();
        }
        WorkerSweepPage page = mechanism.observeRecovery(
                workerGroupId,
                sweep.cursor,
                limit
        );
        advanceSweep(
                sweep,
                page,
                nowMillis,
                config.probeSweepRestartDelayMillis()
        );
        return page.candidates();
    }

    private static void advanceSweep(
            ProbeScoreSweep sweep,
            WorkerSweepPage page,
            long nowMillis,
            long restartDelayMillis
    ) {
        if (!page.isEmpty()) {
            sweep.cursor = page.nextCursor();
            sweep.resumeAtMillis = 0;
            return;
        }
        sweep.cursor = WorkerSweepCursor.start();
        sweep.resumeAtMillis = safeAdd(nowMillis, restartDelayMillis);
    }

    private static boolean eligible(
            WorkerServiceabilityObservation worker,
            long nowMillis,
            long hotEligibilityFloorMillis,
            long recoveryRetryIntervalMillis
    ) {
        if (worker.polarity() == ServiceabilityPolarity.HOT) {
            return worker.timeMillis() < hotEligibilityFloorMillis;
        }
        long multiplier = worker.laneRank() + 1L;
        if (recoveryRetryIntervalMillis > Long.MAX_VALUE / multiplier) {
            return false;
        }
        long delay = multiplier * recoveryRetryIntervalMillis;
        return delay <= nowMillis
                && worker.timeMillis() <= nowMillis - delay;
    }

    private static long safeAdd(long left, long right) {
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private int offerProbes(List<WorkerServiceabilityObservation> workers) {
        Map<String, List<String>> workerIdsByAdapter = new LinkedHashMap<>();
        workers.forEach(worker -> workerIdsByAdapter.computeIfAbsent(
                worker.endpointManagerId(),
                ignored -> new ArrayList<>()
        ).add(worker.workerId()));
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

    private static final class GroupSweepState {
        private final ProbeScoreSweep hot = new ProbeScoreSweep();
        private final ProbeScoreSweep recovery = new ProbeScoreSweep();
    }

    private static final class ProbeScoreSweep {
        private WorkerSweepCursor cursor = WorkerSweepCursor.start();
        private long resumeAtMillis;
    }
}
