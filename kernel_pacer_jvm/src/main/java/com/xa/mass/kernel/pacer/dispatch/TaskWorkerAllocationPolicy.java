package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.assignment.CandidateWorkerCache.CandidateWorkerEntry;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

final class TaskWorkerAllocationPolicy {

    private final WorkerCandidateSelectionPolicy candidateSelection;
    private final WorkerScoreCore workerScores;
    private final CandidateWorkerCache candidateCache;
    private final LongSupplier currentTimeMillis;

    TaskWorkerAllocationPolicy(
            WorkerCandidateSelectionPolicy candidateSelection,
            WorkerScoreCore workerScores,
            CandidateWorkerCache candidateCache
    ) {
        this(
                candidateSelection,
                workerScores,
                candidateCache,
                System::currentTimeMillis
        );
    }

    TaskWorkerAllocationPolicy(
            WorkerCandidateSelectionPolicy candidateSelection,
            WorkerScoreCore workerScores,
            CandidateWorkerCache candidateCache,
            LongSupplier currentTimeMillis
    ) {
        this.candidateSelection = Objects.requireNonNull(
                candidateSelection,
                "candidateSelection"
        );
        this.workerScores = Objects.requireNonNull(
                workerScores,
                "workerScores"
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
        int published = 0;
        for (Map.Entry<String, LinkedHashMap<String, WorkerCandidateRequest>>
                group : requestsByGroup.entrySet()) {
            Map<String, List<AcquiredWorkerCandidate>> acquired =
                    candidateSelection.acquireHotPoolCandidates(
                            group.getKey(),
                            group.getValue(),
                            leaseUntil
                    );
            for (Map.Entry<String, List<AcquiredWorkerCandidate>> candidate
                    : acquired.entrySet()) {
                List<String> workerIds = candidate.getValue().stream()
                        .map(AcquiredWorkerCandidate::workerId)
                        .toList();
                if (workerIds.isEmpty()) {
                    continue;
                }
                Map<String, Long> activeLeases =
                        workerScores.observeActiveHotScoreLeases(
                                group.getKey(),
                                workerIds,
                                leaseUntil
                        );
                List<CandidateWorkerEntry> entries = activeLeases.entrySet()
                        .stream()
                        .map(entry -> new CandidateWorkerEntry(
                                entry.getKey(),
                                group.getKey(),
                                entry.getValue()
                        ))
                        .toList();
                if (entries.isEmpty()) {
                    continue;
                }
                candidateCache.appendCandidateWorkers(
                        candidate.getKey(),
                        entries,
                        leaseUntil
                );
                published++;
            }
        }
        return published;
    }
}
