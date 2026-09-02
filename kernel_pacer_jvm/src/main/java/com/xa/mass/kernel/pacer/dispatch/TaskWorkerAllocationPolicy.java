package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.assignment.CandidateWorkerCache.CandidateWorkerEntry;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime.DemandOfferStatus;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime.TaskRuleMatchDemand;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime.TaskRuleMatchEvidence;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import java.util.ArrayList;
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

        List<String> taskIds = precomputedTasks.stream()
                .map(DueTaskObservation::taskId)
                .toList();
        Map<String, Integer> candidateCounts = new LinkedHashMap<>(
                candidateCache.candidateWorkerCounts(taskIds)
        );
        Map<String, TaskRuleMatchEvidence> evidence =
                workerMatches.takeTaskEvidence(taskIds);
        long now = currentTimeMillis.getAsLong();
        LinkedHashMap<String, LinkedHashMap<String, WorkerCandidateRequest>>
                requestsByGroup = new LinkedHashMap<>();
        LinkedHashMap<String, LinkedHashMap<String, List<String>>>
                matchesByGroup = new LinkedHashMap<>();
        LinkedHashMap<String, LinkedHashMap<String, Long>>
                holdUntilByGroup = new LinkedHashMap<>();
        for (DueTaskObservation task : precomputedTasks) {
            TaskDescriptor descriptor = task.descriptor();
            int requested = deficit(descriptor, candidateCounts, task.taskId());
            TaskRuleMatchEvidence matched = evidence.get(task.taskId());
            if (requested == 0 || matched == null
                    || matched.holdUntilMillis() <= now
                    || !descriptor.workerGroupId().equals(
                            matched.workerGroupId()
                    )) {
                continue;
            }
            requestsByGroup.computeIfAbsent(
                    descriptor.workerGroupId(),
                    ignored -> new LinkedHashMap<>()
            ).put(task.taskId(), new WorkerCandidateRequest(
                    priority(descriptor),
                    requested
            ));
            matchesByGroup.computeIfAbsent(
                    descriptor.workerGroupId(),
                    ignored -> new LinkedHashMap<>()
            ).put(task.taskId(), matched.matchedWorkerIds());
            holdUntilByGroup.computeIfAbsent(
                    descriptor.workerGroupId(),
                    ignored -> new LinkedHashMap<>()
            ).put(task.taskId(), matched.holdUntilMillis());
        }

        int published = 0;
        for (Map.Entry<String, LinkedHashMap<String, WorkerCandidateRequest>>
                group : requestsByGroup.entrySet()) {
            Map<String, List<AcquiredWorkerCandidate>> acquired =
                    candidateSelection.selectHeldCandidates(
                            group.getKey(),
                            group.getValue(),
                            matchesByGroup.get(group.getKey()),
                            holdUntilByGroup.get(group.getKey()),
                            WorkerCandidateSelectionPolicy
                                    .MAX_UNIQUE_WORKERS_PER_ROUND
                    );
            for (Map.Entry<String, List<AcquiredWorkerCandidate>> candidate
                    : acquired.entrySet()) {
                List<String> workerIds = candidate.getValue().stream()
                        .map(AcquiredWorkerCandidate::workerId)
                        .toList();
                if (workerIds.isEmpty()) {
                    continue;
                }
                List<CandidateWorkerEntry> entries = candidate.getValue()
                        .stream()
                        .map(worker -> new CandidateWorkerEntry(
                                worker.workerId(),
                                worker.workerGroupId(),
                                worker.workerLeaseScore()
                        ))
                        .toList();
                if (entries.isEmpty()) {
                    continue;
                }
                candidateCache.appendCandidateWorkers(
                        candidate.getKey(),
                        entries,
                        holdUntilByGroup.get(group.getKey())
                                .get(candidate.getKey())
                );
                candidateCounts.merge(
                        candidate.getKey(),
                        entries.size(),
                        Integer::sum
                );
                published++;
            }
        }

        publishDemands(precomputedTasks, candidateCounts, now, config);
        return published;
    }

    private void publishDemands(
            List<DueTaskObservation> tasks,
            Map<String, Integer> candidateCounts,
            long now,
            TaskWorkerAllocationConfig config
    ) {
        LinkedHashMap<String, List<DueTaskObservation>> byGroup =
                new LinkedHashMap<>();
        for (DueTaskObservation task : tasks) {
            if (deficit(
                    task.descriptor(),
                    candidateCounts,
                    task.taskId()
            ) > 0) {
                byGroup.computeIfAbsent(
                        task.descriptor().workerGroupId(),
                        ignored -> new ArrayList<>()
                ).add(task);
            }
        }
        for (Map.Entry<String, List<DueTaskObservation>> group
                : byGroup.entrySet()) {
            Map<String, Long> observed =
                    candidateSelection.observeDueCandidates(group.getKey());
            if (observed.isEmpty()) {
                continue;
            }
            long holdUntil = Math.addExact(
                    now,
                    config.workerLeaseDurationMillis()
            );
            List<String> workerIds = List.copyOf(observed.keySet());
            List<TaskRuleMatchDemand> demands = group.getValue().stream()
                    .map(task -> new TaskRuleMatchDemand(
                            task.taskId(),
                            group.getKey(),
                            workerIds,
                            holdUntil
                    ))
                    .toList();
            Map<String, DemandOfferStatus> statuses =
                    workerMatches.offerTaskDemands(demands);
            if (statuses.values().stream().anyMatch(
                    status -> status == DemandOfferStatus.OFFERED
            )) {
                candidateSelection.holdObservedCandidates(
                        group.getKey(),
                        observed,
                        holdUntil
                );
            }
        }
    }

    private static int deficit(
            TaskDescriptor descriptor,
            Map<String, Integer> candidateCounts,
            String taskId
    ) {
        int maximum = Integer.parseInt(
                descriptor.config().get("maximumCandidateWorkers")
        );
        return Math.max(0, maximum - candidateCounts.getOrDefault(taskId, 0));
    }

    private static int priority(TaskDescriptor descriptor) {
        return Integer.parseInt(descriptor.config().get("priority"));
    }
}
