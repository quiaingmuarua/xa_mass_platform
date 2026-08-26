package com.xa.mass.kernel.pacer;

import com.xa.mass.kernel.assignment.CandidateWarmupSchedule;
import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.assignment.CandidateWorkerCache.CandidateWorkerEntry;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreState;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

final class TaskWorkerAllocationPacer {

    private final CandidateWarmupSchedule warmups;
    private final TaskScoreBandCore taskScore;
    private final TaskResourceCatalog taskCatalog;
    private final WorkerCandidateAcquirer candidateAcquirer;
    private final CandidateWorkerCache candidateCache;
    private final LongSupplier currentTimeMillis;

    public TaskWorkerAllocationPacer(
            CandidateWarmupSchedule warmups,
            TaskScoreBandCore taskScore,
            TaskResourceCatalog taskCatalog,
            WorkerCandidateAcquirer candidateAcquirer,
            CandidateWorkerCache candidateCache
    ) {
        this(
                warmups,
                taskScore,
                taskCatalog,
                candidateAcquirer,
                candidateCache,
                System::currentTimeMillis
        );
    }

    TaskWorkerAllocationPacer(
            CandidateWarmupSchedule warmups,
            TaskScoreBandCore taskScore,
            TaskResourceCatalog taskCatalog,
            WorkerCandidateAcquirer candidateAcquirer,
            CandidateWorkerCache candidateCache,
            LongSupplier currentTimeMillis
    ) {
        this.warmups = java.util.Objects.requireNonNull(warmups, "warmups");
        this.taskScore = java.util.Objects.requireNonNull(
                taskScore,
                "taskScore"
        );
        this.taskCatalog = java.util.Objects.requireNonNull(
                taskCatalog,
                "taskCatalog"
        );
        this.candidateAcquirer = java.util.Objects.requireNonNull(
                candidateAcquirer,
                "candidateAcquirer"
        );
        this.candidateCache = java.util.Objects.requireNonNull(
                candidateCache,
                "candidateCache"
        );
        this.currentTimeMillis = java.util.Objects.requireNonNull(
                currentTimeMillis,
                "currentTimeMillis"
        );
    }

    public int allocateCandidateWorkers(TaskWorkerAllocationConfig config) {
        java.util.Objects.requireNonNull(config, "config");
        long nowMillis = currentTimeMillis.getAsLong();
        List<String> taskIds = warmups.consumeDueCandidateWarmups(
                nowMillis,
                config.taskBatchLimit()
        );
        if (taskIds.isEmpty()) {
            return 0;
        }
        Map<String, TaskScoreState> states = taskScore.getScoreStates(taskIds);
        List<String> running = taskIds.stream()
                .filter(taskId -> {
                    TaskScoreState state = states.get(taskId);
                    return state != null
                            && state.band() == TaskScoreBand.RUNNING_VISIBLE
                            && state.timeMillis() != null
                            && state.timeMillis()
                            != TaskScoreBandCore.PAUSE_TIME_MILLIS
                            && state.suffix() != null
                            && state.suffix() == TaskScoreBandCore.MIN_SUFFIX;
                })
                .toList();
        if (running.isEmpty()) {
            return 0;
        }
        Map<String, TaskDescriptor> descriptors =
                taskCatalog.loadTaskAllocationDescriptors(running);
        List<String> precomputed = running.stream()
                .filter(taskId -> {
                    TaskDescriptor descriptor = descriptors.get(taskId);
                    return descriptor != null
                            && descriptor.workerAllocationMechanism()
                            == WorkerAllocationMechanism
                                    .PRECOMPUTED_TASK_RULE;
                })
                .toList();
        Map<String, Integer> counts = precomputed.isEmpty()
                ? Map.of()
                : candidateCache.candidateWorkerCounts(precomputed);
        LinkedHashMap<String, LinkedHashMap<String, WorkerCandidateRequest>>
                requestsByGroup = new LinkedHashMap<>();
        for (String taskId : precomputed) {
            TaskDescriptor descriptor = descriptors.get(taskId);
            int maximum = Integer.parseInt(
                    descriptor.config().get("maximumCandidateWorkers")
            );
            int requested = Math.max(0, maximum - counts.getOrDefault(
                    taskId,
                    0
            ));
            if (requested == 0 || descriptor.allocationRule() == null) {
                continue;
            }
            requestsByGroup.computeIfAbsent(
                    descriptor.workerGroupId(),
                    ignored -> new LinkedHashMap<>()
            ).put(taskId, new WorkerCandidateRequest(
                    Integer.parseInt(descriptor.config().get("priority")),
                    requested,
                    descriptor.allocationRule()
            ));
        }
        long leaseUntil = Math.addExact(
                nowMillis,
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
        List<String> incomplete = new ArrayList<>();
        requestsByGroup.values().forEach(requests -> requests.forEach(
                (taskId, request) -> {
                    if (acquired.getOrDefault(taskId, List.of()).size()
                            < request.requestedCount()) {
                        incomplete.add(taskId);
                    }
                }
        ));
        if (!incomplete.isEmpty()) {
            warmups.scheduleCandidateWarmups(incomplete, nowMillis);
        }
        return published;
    }
}
