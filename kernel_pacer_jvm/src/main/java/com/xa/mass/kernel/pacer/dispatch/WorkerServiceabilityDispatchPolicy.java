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

    private final WorkerServiceabilityDispatchMechanism mechanism;
    private final WorkerServiceabilityRuntime serviceability;
    private final LongSupplier currentTimeMillis;
    private final LinkedHashMap<String, GroupSweepState> groupSweeps =
            new LinkedHashMap<>();
    private int groupCursor;

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
            List<DueTaskObservation> tasks,
            WorkerServiceabilityDispatchConfig config,
            long hotEligibilityFloorMillis
    ) {
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(config, "config");
        WorkerServiceabilityDispatchAssemblyConfig.requireFloor(
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
        GroupSweepState sweepState = sweepState(workerGroupId);
        List<WorkerCandidateReference> hot = hotPage(
                workerGroupId,
                sweepState.hot,
                hotEligibilityFloorMillis,
                nowMillis,
                config
        );
        List<WorkerCandidateReference> recovery = recoveryPage(
                workerGroupId,
                sweepState.recovery,
                nowMillis,
                config
        );
        LinkedHashMap<String, WorkerCandidateReference> candidates =
                new LinkedHashMap<>();
        hot.forEach(reference -> candidates.putIfAbsent(
                reference.workerId(),
                reference
        ));
        recovery.forEach(reference -> candidates.putIfAbsent(
                reference.workerId(),
                reference
        ));
        if (candidates.isEmpty()) {
            return 0;
        }

        List<WorkerServiceabilityObservation> rechecked = mechanism.recheck(
                workerGroupId,
                List.copyOf(candidates.values())
        );
        Set<String> excludedEndpoints = Set.copyOf(
                config.probeExcludedEndpointManagerIds()
        );
        List<WorkerServiceabilityObservation> excluded = new ArrayList<>();
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
            } else {
                probe.add(worker);
            }
        }
        mechanism.coldParkExcluded(
                excluded,
                config.maxRecoveryAttempts()
        );
        return offerProbes(probe);
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
            WorkerServiceabilityDispatchConfig config
    ) {
        if (nowMillis < sweep.resumeAtMillis) {
            return List.of();
        }
        WorkerSweepPage page = mechanism.observePreEpochHot(
                workerGroupId,
                hotEligibilityFloorMillis,
                sweep.cursor,
                config.hotScanLimit()
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
            WorkerServiceabilityDispatchConfig config
    ) {
        if (nowMillis < sweep.resumeAtMillis) {
            return List.of();
        }
        WorkerSweepPage page = mechanism.observeRecovery(
                workerGroupId,
                sweep.cursor,
                config.recoveryScanLimit()
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
