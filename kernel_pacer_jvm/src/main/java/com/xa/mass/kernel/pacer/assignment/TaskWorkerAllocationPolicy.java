package com.xa.mass.kernel.pacer;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.assignment.CandidateWorkerCache.CandidateWorkerEntry;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

final class TaskWorkerAllocationPolicy {

    private final WorkerCandidateAcquirer candidateAcquirer;
    private final CandidateWorkerCache candidateCache;
    private final LongSupplier currentTimeMillis;

    TaskWorkerAllocationPolicy(
            WorkerCandidateAcquirer candidateAcquirer,
            CandidateWorkerCache candidateCache
    ) {
        this(candidateAcquirer, candidateCache, System::currentTimeMillis);
    }

    TaskWorkerAllocationPolicy(
            WorkerCandidateAcquirer candidateAcquirer,
            CandidateWorkerCache candidateCache,
            LongSupplier currentTimeMillis
    ) {
        this.candidateAcquirer = Objects.requireNonNull(
                candidateAcquirer,
                "candidateAcquirer"
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
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(config, "config");
        List<DueTaskObservation> precomputed = tasks.stream()
                .filter(task -> task.descriptor().workerAllocationMechanism()
                        == WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE)
                .toList();
        if (precomputed.isEmpty()) {
            return 0;
        }
        List<String> taskIds = precomputed.stream()
                .map(DueTaskObservation::taskId)
                .toList();
        Map<String, Integer> counts = candidateCache.candidateWorkerCounts(
                taskIds
        );
        LinkedHashMap<String, LinkedHashMap<String, WorkerCandidateRequest>>
                requestsByGroup = new LinkedHashMap<>();
        for (DueTaskObservation task : precomputed) {
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
        LinkedHashMap<String, List<CandidateWorkerEntry>> acquired =
                new LinkedHashMap<>();
        requestsByGroup.forEach((workerGroupId, requests) -> acquired.putAll(
                candidateAcquirer.acquireHotPoolCandidates(
                        workerGroupId,
                        requests,
                        leaseUntil
                )
        ));
        int published = 0;
        for (Map.Entry<String, List<CandidateWorkerEntry>> entry
                : acquired.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                candidateCache.appendCandidateWorkers(
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
