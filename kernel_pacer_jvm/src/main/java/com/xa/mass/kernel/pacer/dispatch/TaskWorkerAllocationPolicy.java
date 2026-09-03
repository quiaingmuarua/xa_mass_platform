package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime.TaskCandidateNeed;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime.TaskRuleMatchDemand;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

final class TaskWorkerAllocationPolicy {

    private final WorkerCandidateSelectionPolicy candidateSelection;
    private final CandidateWorkerCache candidateCache;
    private final WorkerMatchRuntime workerMatches;
    private final LongSupplier currentTimeMillis;

    TaskWorkerAllocationPolicy(
            WorkerCandidateSelectionPolicy candidateSelection,
            CandidateWorkerCache candidateCache,
            WorkerMatchRuntime workerMatches
    ) {
        this(
                candidateSelection,
                candidateCache,
                workerMatches,
                System::currentTimeMillis
        );
    }

    TaskWorkerAllocationPolicy(
            WorkerCandidateSelectionPolicy candidateSelection,
            CandidateWorkerCache candidateCache,
            WorkerMatchRuntime workerMatches,
            LongSupplier currentTimeMillis
    ) {
        this.candidateSelection = Objects.requireNonNull(
                candidateSelection,
                "candidateSelection"
        );
        this.candidateCache = Objects.requireNonNull(
                candidateCache,
                "candidateCache"
        );
        this.workerMatches = Objects.requireNonNull(
                workerMatches,
                "workerMatches"
        );
        this.currentTimeMillis = Objects.requireNonNull(
                currentTimeMillis,
                "currentTimeMillis"
        );
    }

    int allocateCandidateWorkers(
            List<DueTaskObservation> tasks,
            TaskWorkerAllocationConfig config
    ) {
        List<DueTaskObservation> precomputedTasks = List.copyOf(
                Objects.requireNonNull(tasks, "tasks")
        );
        Objects.requireNonNull(config, "config");
        if (precomputedTasks.stream().anyMatch(task ->
                task.descriptor().workerAllocationMechanism()
                        != WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE)) {
            throw new IllegalArgumentException(
                    "Worker Allocation requires PRECOMPUTED Task inputs"
            );
        }
        if (precomputedTasks.isEmpty()) {
            return 0;
        }

        List<String> candidateIds = precomputedTasks.stream()
                .map(DueTaskObservation::taskId)
                .toList();
        Map<String, Integer> candidateCounts =
                candidateCache.candidateWorkerCounts(candidateIds);
        LinkedHashMap<String, List<DueTaskObservation>> tasksByGroup =
                new LinkedHashMap<>();
        for (DueTaskObservation task : precomputedTasks) {
            if (deficit(task, candidateCounts) > 0) {
                tasksByGroup.computeIfAbsent(
                        task.descriptor().workerGroupId(),
                        ignored -> new ArrayList<>()
                ).add(task);
            }
        }

        long now = currentTimeMillis.getAsLong();
        int offered = 0;
        for (Map.Entry<String, List<DueTaskObservation>> group
                : tasksByGroup.entrySet()) {
            List<DueTaskObservation> ordered = group.getValue().stream()
                    .sorted(Comparator
                            .comparingInt(TaskWorkerAllocationPolicy::priority)
                            .thenComparing(DueTaskObservation::taskId))
                    .limit(WorkerMatchRuntime.MAX_TASKS_PER_DEMAND)
                    .toList();
            int requestedWorkers = requestedWorkers(
                    ordered,
                    candidateCounts
            );
            if (requestedWorkers == 0) {
                continue;
            }
            Map<String, Long> observed = candidateSelection
                    .observeDueCandidates(group.getKey(), requestedWorkers);
            if (observed.isEmpty()) {
                continue;
            }
            long holdUntil = Math.addExact(
                    now,
                    config.workerLeaseDurationMillis()
            );
            Map<String, Long> held = candidateSelection
                    .holdObservedCandidates(
                            group.getKey(),
                            observed,
                            holdUntil
                    );
            if (held.isEmpty()) {
                continue;
            }
            List<TaskCandidateNeed> needs = ordered.stream()
                    .map(task -> new TaskCandidateNeed(
                            task.taskId(),
                            maximumCandidates(task.descriptor())
                    ))
                    .toList();
            if (workerMatches.offerTaskDemand(new TaskRuleMatchDemand(
                    group.getKey(),
                    needs,
                    held,
                    holdUntil
            ))) {
                offered++;
            }
        }
        return offered;
    }

    private static int requestedWorkers(
            List<DueTaskObservation> tasks,
            Map<String, Integer> candidateCounts
    ) {
        int requested = 0;
        for (DueTaskObservation task : tasks) {
            int remaining = WorkerMatchRuntime.MAX_HELD_WORKERS_PER_DEMAND
                    - requested;
            requested += Math.min(
                    remaining,
                    deficit(task, candidateCounts)
            );
            if (requested
                    == WorkerMatchRuntime.MAX_HELD_WORKERS_PER_DEMAND) {
                break;
            }
        }
        return requested;
    }

    private static int deficit(
            DueTaskObservation task,
            Map<String, Integer> candidateCounts
    ) {
        return Math.max(
                0,
                maximumCandidates(task.descriptor())
                        - candidateCounts.getOrDefault(task.taskId(), 0)
        );
    }

    private static int maximumCandidates(TaskDescriptor descriptor) {
        return Integer.parseInt(
                descriptor.config().get("maximumCandidateWorkers")
        );
    }

    private static int priority(DueTaskObservation task) {
        return Integer.parseInt(task.descriptor().config().get("priority"));
    }
}
