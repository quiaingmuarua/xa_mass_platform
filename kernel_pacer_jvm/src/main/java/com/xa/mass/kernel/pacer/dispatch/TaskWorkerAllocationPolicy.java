package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.assignment.TaskRuleMatchDemand;
import com.xa.mass.kernel.assignment.TaskRuleMatchDemand.TaskCandidateNeed;
import com.xa.mass.kernel.assignment.WorkerMatchQueue;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

final class TaskWorkerAllocationPolicy {

    static final long WORKER_HOLD_MILLIS = 5_000;

    private final WorkerScoreCore workerScores;
    private final CandidateWorkerCache candidateCache;
    private final WorkerMatchQueue matchQueue;
    private final Long hotEligibilityFloorMillis;
    private final LongSupplier currentTimeMillis;

    TaskWorkerAllocationPolicy(
            WorkerScoreCore workerScores,
            CandidateWorkerCache candidateCache,
            WorkerMatchQueue matchQueue,
            Long hotEligibilityFloorMillis
    ) {
        this(
                workerScores,
                candidateCache,
                matchQueue,
                hotEligibilityFloorMillis,
                System::currentTimeMillis
        );
    }

    TaskWorkerAllocationPolicy(
            WorkerScoreCore workerScores,
            CandidateWorkerCache candidateCache,
            WorkerMatchQueue matchQueue,
            Long hotEligibilityFloorMillis,
            LongSupplier currentTimeMillis
    ) {
        this.workerScores = Objects.requireNonNull(
                workerScores,
                "workerScores"
        );
        this.candidateCache = Objects.requireNonNull(
                candidateCache,
                "candidateCache"
        );
        this.matchQueue = Objects.requireNonNull(
                matchQueue,
                "matchQueue"
        );
        this.hotEligibilityFloorMillis = hotEligibilityFloorMillis;
        this.currentTimeMillis = Objects.requireNonNull(
                currentTimeMillis,
                "currentTimeMillis"
        );
    }

    int allocateCandidateWorkers(
            List<CandidateAllocationNeed> needs
    ) {
        List<CandidateAllocationNeed> allocationNeeds = List.copyOf(
                Objects.requireNonNull(needs, "needs")
        );
        if (allocationNeeds.isEmpty()) {
            return 0;
        }

        List<String> candidateIds = allocationNeeds.stream()
                .map(CandidateAllocationNeed::candidateId)
                .toList();
        Map<String, Integer> candidateCounts =
                candidateCache.candidateWorkerCounts(candidateIds);
        LinkedHashMap<String, List<CandidateAllocationNeed>> needsByGroup =
                new LinkedHashMap<>();
        for (CandidateAllocationNeed need : allocationNeeds) {
            if (deficit(need, candidateCounts) > 0) {
                needsByGroup.computeIfAbsent(
                        need.workerGroupId(),
                        ignored -> new ArrayList<>()
                ).add(need);
            }
        }

        long now = currentTimeMillis.getAsLong();
        int offered = 0;
        for (Map.Entry<String, List<CandidateAllocationNeed>> group
                : needsByGroup.entrySet()) {
            List<CandidateAllocationNeed> ordered = group.getValue().stream()
                    .sorted(Comparator
                            .comparingInt(CandidateAllocationNeed::priority)
                            .thenComparing(
                                    CandidateAllocationNeed::candidateId
                            ))
                    .limit(TaskRuleMatchDemand.MAX_TASKS)
                    .toList();
            int requestedWorkers = requestedWorkers(
                    ordered,
                    candidateCounts
            );
            if (requestedWorkers == 0) {
                continue;
            }
            Map<String, Long> observed = workerScores
                    .observeDueHotScoreCandidates(
                            group.getKey(),
                            hotEligibilityFloorMillis,
                            requestedWorkers
                    );
            if (observed.isEmpty()) {
                continue;
            }
            long holdUntil = Math.addExact(
                    now,
                    WORKER_HOLD_MILLIS
            );
            Map<String, Long> held = holdObservedCandidates(
                    group.getKey(),
                    observed,
                    holdUntil
            );
            if (held.isEmpty()) {
                continue;
            }
            List<TaskCandidateNeed> taskNeeds = ordered.stream()
                    .map(need -> new TaskCandidateNeed(
                            need.candidateId(),
                            need.maximumCandidateWorkers()
                    ))
                    .toList();
            if (matchQueue.offer(new TaskRuleMatchDemand(
                    group.getKey(),
                    taskNeeds,
                    held,
                    holdUntil
            ))) {
                offered++;
            }
        }
        return offered;
    }

    private Map<String, Long> holdObservedCandidates(
            String workerGroupId,
            Map<String, Long> observedScores,
            long holdUntilMillis
    ) {
        Map<String, WorkerScoreTransitionResult> transitions =
                workerScores.acquireObservedHotScoreLeases(
                        workerGroupId,
                        observedScores,
                        holdUntilMillis
                );
        LinkedHashMap<String, Long> held = new LinkedHashMap<>();
        observedScores.keySet().forEach(workerId -> {
            WorkerScoreTransitionResult result = transitions.get(workerId);
            if (result != null
                    && result.status()
                            == WorkerScoreTransitionStatus.TRANSITIONED
                    && result.score() != null) {
                held.put(workerId, result.score());
            }
        });
        return Collections.unmodifiableMap(held);
    }

    private static int requestedWorkers(
            List<CandidateAllocationNeed> needs,
            Map<String, Integer> candidateCounts
    ) {
        int requested = 0;
        for (CandidateAllocationNeed need : needs) {
            int remaining = TaskRuleMatchDemand.MAX_HELD_WORKERS
                    - requested;
            requested += Math.min(
                    remaining,
                    deficit(need, candidateCounts)
            );
            if (requested
                    == TaskRuleMatchDemand.MAX_HELD_WORKERS) {
                break;
            }
        }
        return requested;
    }

    private static int deficit(
            CandidateAllocationNeed need,
            Map<String, Integer> candidateCounts
    ) {
        return Math.max(
                0,
                need.maximumCandidateWorkers()
                        - candidateCounts.getOrDefault(
                                need.candidateId(),
                                0
                        )
        );
    }
}
