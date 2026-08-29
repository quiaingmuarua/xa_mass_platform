package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.pacer.dispatch.WorkerCandidateMechanism.WorkerCandidateObservation;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

final class TaskWorkerAllocationPolicy {

    private final WorkerCandidateSelectionPolicy candidateSelection;
    private final WorkerCandidateMechanism candidateMechanism;
    private final CandidateWorkerCache candidateCache;
    private final LongSupplier currentTimeMillis;

    TaskWorkerAllocationPolicy(
            WorkerCandidateSelectionPolicy candidateSelection,
            WorkerCandidateMechanism candidateMechanism,
            CandidateWorkerCache candidateCache
    ) {
        this(
                candidateSelection,
                candidateMechanism,
                candidateCache,
                System::currentTimeMillis
        );
    }

    TaskWorkerAllocationPolicy(
            WorkerCandidateSelectionPolicy candidateSelection,
            WorkerCandidateMechanism candidateMechanism,
            CandidateWorkerCache candidateCache,
            LongSupplier currentTimeMillis
    ) {
        this.candidateSelection = Objects.requireNonNull(
                candidateSelection,
                "candidateSelection"
        );
        this.candidateMechanism = Objects.requireNonNull(
                candidateMechanism,
                "candidateMechanism"
        );
        this.candidateCache = Objects.requireNonNull(
                candidateCache,
                "candidateCache"
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
        List<String> taskIds = precomputedTasks.stream()
                .map(DueTaskObservation::taskId)
                .toList();
        Map<String, Integer> counts = candidateCache.candidateWorkerCounts(
                taskIds
        );
        LinkedHashMap<String, LinkedHashMap<String, WorkerCandidateRequest>>
                requestsByGroup = new LinkedHashMap<>();
        for (DueTaskObservation task : precomputedTasks) {
            TaskDescriptor descriptor = task.descriptor();
            int maximum = Integer.parseInt(
                    descriptor.config().get("maximumCandidateWorkers")
            );
            int requested = Math.max(0, maximum - counts.getOrDefault(
                    task.taskId(),
                    0
            ));
            if (requested == 0 || descriptor.allocationRule() == null) {
                continue;
            }
            requestsByGroup.computeIfAbsent(
                    descriptor.workerGroupId(),
                    ignored -> new LinkedHashMap<>()
            ).put(task.taskId(), new WorkerCandidateRequest(
                    Integer.parseInt(descriptor.config().get("priority")),
                    requested,
                    descriptor.allocationRule()
            ));
        }
        long leaseUntil = Math.addExact(
                currentTimeMillis.getAsLong(),
                config.workerLeaseDurationMillis()
        );
        LinkedHashMap<String, List<WorkerCandidateObservation>> acquired =
                new LinkedHashMap<>();
        requestsByGroup.forEach((workerGroupId, requests) -> acquired.putAll(
                candidateSelection.acquireHotPoolCandidates(
                        workerGroupId,
                        requests,
                        leaseUntil
                )
        ));
        int published = 0;
        for (Map.Entry<String, List<WorkerCandidateObservation>> entry
                : acquired.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                candidateMechanism.appendCandidates(
                        entry.getKey(),
                        entry.getValue(),
                        leaseUntil
                );
                published++;
            }
        }
        return published;
    }
}
