package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreBand;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreObservation;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

final class TaskDispatchPolicy {

    private final TaskScoreBandCore taskScores;
    private final TaskItemScoreBandCore itemScores;
    private final TaskRuntime taskRuntime;
    private final TaskAssignmentDispatcher assignmentDispatcher;
    private final TaskIdleSettlement idleSettlement;
    private final WorkerCandidateSelectionPolicy candidateSelection;
    private final LongSupplier currentTimeMillis;

    TaskDispatchPolicy(
            TaskScoreBandCore taskScores,
            TaskItemScoreBandCore itemScores,
            TaskRuntime taskRuntime,
            TaskAssignmentDispatcher assignmentDispatcher,
            TaskIdleSettlement idleSettlement,
            WorkerCandidateSelectionPolicy candidateSelection
    ) {
        this(
                taskScores,
                itemScores,
                taskRuntime,
                assignmentDispatcher,
                idleSettlement,
                candidateSelection,
                System::currentTimeMillis
        );
    }

    TaskDispatchPolicy(
            TaskScoreBandCore taskScores,
            TaskItemScoreBandCore itemScores,
            TaskRuntime taskRuntime,
            TaskAssignmentDispatcher assignmentDispatcher,
            TaskIdleSettlement idleSettlement,
            WorkerCandidateSelectionPolicy candidateSelection,
            LongSupplier currentTimeMillis
    ) {
        this.taskScores = Objects.requireNonNull(taskScores, "taskScores");
        this.itemScores = Objects.requireNonNull(itemScores, "itemScores");
        this.taskRuntime = Objects.requireNonNull(taskRuntime, "taskRuntime");
        this.assignmentDispatcher = Objects.requireNonNull(
                assignmentDispatcher,
                "assignmentDispatcher"
        );
        this.idleSettlement = Objects.requireNonNull(
                idleSettlement,
                "idleSettlement"
        );
        this.candidateSelection = Objects.requireNonNull(
                candidateSelection,
                "candidateSelection"
        );
        this.currentTimeMillis = Objects.requireNonNull(
                currentTimeMillis,
                "currentTimeMillis"
        );
    }

    int dispatchTasks(
            List<DueTaskObservation> tasks,
            TaskDispatchConfig config
    ) {
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(config, "config");
        long dispatchTimeMillis = currentTimeMillis.getAsLong();
        long claimUntilMillis = Math.addExact(
                dispatchTimeMillis,
                config.itemClaimLeaseDurationMillis()
        );
        Set<String> roundWorkerIds = new LinkedHashSet<>();
        int published = 0;
        for (DueTaskObservation task : tasks) {
            Map<String, TaskItemScoreObservation> observed =
                    itemScores.acquireItemScoreCandidates(
                            task.taskId(),
                            config.perTaskDispatchLimit()
                    );
            List<String> loadIds = observed.entrySet().stream()
                    .filter(entry -> entry.getValue().remainingBudget() > 0)
                    .map(Map.Entry::getKey)
                    .toList();
            Map<String, TaskItem> items = loadIds.isEmpty()
                    ? Map.of()
                    : taskRuntime.loadTaskItems(task.taskId(), loadIds);

            List<String> failedIds = observed.entrySet().stream()
                    .filter(entry -> failed(
                            entry.getValue(),
                            items.get(entry.getKey()),
                            dispatchTimeMillis
                    ))
                    .map(Map.Entry::getKey)
                    .toList();
            if (!failedIds.isEmpty()) {
                taskRuntime.storeTaskItemFailedResults(
                        task.taskId(),
                        failedIds
                );
                itemScores.promoteItemOutcomes(
                        task.taskId(),
                        failedIds,
                        TaskItemScoreBand.FINAL_FAILED,
                        dispatchTimeMillis
                );
            }
            Set<String> failed = Set.copyOf(failedIds);
            List<String> claimableIds = observed.entrySet().stream()
                    .filter(entry -> entry.getValue().remainingBudget() > 0)
                    .map(Map.Entry::getKey)
                    .filter(items::containsKey)
                    .filter(messageId -> !failed.contains(messageId))
                    .toList();
            if (claimableIds.isEmpty()) {
                idleSettlement.settle(
                        task,
                        task.descriptor().idleDisposition(),
                        dispatchTimeMillis
                );
                continue;
            }

            try {
                Map<String, AcquiredWorkerCandidate> assignments =
                        assignments(
                                task,
                                claimableIds,
                                items,
                                claimUntilMillis,
                                roundWorkerIds
                        );
                LinkedHashMap<String, TaskItem> assignedItems =
                        new LinkedHashMap<>();
                LinkedHashMap<String, Long> assignedScores =
                        new LinkedHashMap<>();
                assignments.keySet().forEach(messageId -> {
                    assignedItems.put(messageId, items.get(messageId));
                    assignedScores.put(
                            messageId,
                            observed.get(messageId).score()
                    );
                });
                published += assignmentDispatcher.dispatch(
                        task,
                        assignedItems,
                        assignedScores,
                        assignments,
                        claimUntilMillis
                );
            } finally {
                taskScores.rewriteSameBandTimeMillis(
                        task.taskId(),
                        TaskScoreBand.RUNNING_VISIBLE,
                        dispatchTimeMillis
                );
            }
        }
        return published;
    }

    private Map<String, AcquiredWorkerCandidate> assignments(
            DueTaskObservation task,
            List<String> messageIds,
            Map<String, TaskItem> items,
            long leaseUntilMillis,
            Set<String> roundWorkerIds
    ) {
        int priority = Integer.parseInt(
                task.descriptor().config().get("priority")
        );
        Map<String, List<AcquiredWorkerCandidate>> acquired;
        if (task.descriptor().workerAllocationMechanism()
                == WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE) {
            acquired = candidateSelection.renewCachedCandidates(
                    task.descriptor().workerGroupId(),
                    Map.of(task.taskId(), new WorkerCandidateRequest(
                            priority,
                            messageIds.size(),
                            Objects.requireNonNull(
                                    task.descriptor().allocationRule()
                            )
                    )),
                    leaseUntilMillis
            );
            return pair(
                    messageIds,
                    acquired.getOrDefault(task.taskId(), List.of()),
                    roundWorkerIds
            );
        }

        LinkedHashMap<String, WorkerCandidateRequest> requests =
                new LinkedHashMap<>();
        messageIds.forEach(messageId -> requests.put(
                messageId,
                new WorkerCandidateRequest(
                        priority,
                        1,
                        Objects.requireNonNull(
                                items.get(messageId).allocationRule()
                        )
                )
        ));
        acquired = candidateSelection.acquireOnDemandCandidates(
                task.descriptor().workerGroupId(),
                requests,
                leaseUntilMillis
        );
        LinkedHashMap<String, AcquiredWorkerCandidate> result =
                new LinkedHashMap<>();
        for (String messageId : messageIds) {
            List<AcquiredWorkerCandidate> workers = acquired.getOrDefault(
                    messageId,
                    List.of()
            );
            if (!workers.isEmpty()
                    && roundWorkerIds.add(workers.get(0).workerId())) {
                result.put(messageId, workers.get(0));
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private static Map<String, AcquiredWorkerCandidate> pair(
            List<String> messageIds,
            List<AcquiredWorkerCandidate> workers,
            Set<String> roundWorkerIds
    ) {
        LinkedHashMap<String, AcquiredWorkerCandidate> result =
                new LinkedHashMap<>();
        int count = Math.min(messageIds.size(), workers.size());
        for (int index = 0; index < count; index++) {
            AcquiredWorkerCandidate worker = workers.get(index);
            if (roundWorkerIds.add(worker.workerId())) {
                result.put(messageIds.get(index), worker);
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private static boolean failed(
            TaskItemScoreObservation observation,
            TaskItem item,
            long observedAtMillis
    ) {
        return observation.remainingBudget() == 0
                || item != null
                && item.expireAtMillis() != null
                && observedAtMillis >= item.expireAtMillis();
    }
}
