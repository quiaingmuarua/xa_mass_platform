package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.WorkerMatchRuntime;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime.ItemMatchKey;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime.ItemRuleMatchDemand;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime.ItemRuleMatchEvidence;
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
    private final WorkerMatchRuntime workerMatches;
    private final LongSupplier currentTimeMillis;

    TaskDispatchPolicy(
            TaskScoreBandCore taskScores,
            TaskItemScoreBandCore itemScores,
            TaskRuntime taskRuntime,
            TaskAssignmentDispatcher assignmentDispatcher,
            TaskIdleSettlement idleSettlement,
            WorkerCandidateSelectionPolicy candidateSelection,
            WorkerMatchRuntime workerMatches
    ) {
        this(
                taskScores,
                itemScores,
                taskRuntime,
                assignmentDispatcher,
                idleSettlement,
                candidateSelection,
                workerMatches,
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
            WorkerMatchRuntime workerMatches,
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
        this.workerMatches = Objects.requireNonNull(
                workerMatches,
                "workerMatches"
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
                                claimUntilMillis,
                                dispatchTimeMillis,
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
            long leaseUntilMillis,
            long observedAtMillis,
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
                            messageIds.size()
                    )),
                    leaseUntilMillis
            );
            return pair(
                    messageIds,
                    acquired.getOrDefault(task.taskId(), List.of()),
                    roundWorkerIds
            );
        }

        List<ItemMatchKey> matchKeys = messageIds.stream()
                .map(messageId -> new ItemMatchKey(task.taskId(), messageId))
                .toList();
        Map<ItemMatchKey, ItemRuleMatchEvidence> evidence =
                workerMatches.takeItemEvidence(matchKeys);
        LinkedHashMap<String, WorkerCandidateRequest> requests =
                new LinkedHashMap<>();
        LinkedHashMap<String, List<String>> matched = new LinkedHashMap<>();
        LinkedHashMap<String, Long> holdUntilByMessageId =
                new LinkedHashMap<>();
        for (String messageId : messageIds) {
            ItemMatchKey key = new ItemMatchKey(task.taskId(), messageId);
            ItemRuleMatchEvidence itemEvidence = evidence.get(key);
            requests.put(messageId, new WorkerCandidateRequest(priority, 1));
            matched.put(
                    messageId,
                    itemEvidence != null
                            && itemEvidence.holdUntilMillis()
                                    > observedAtMillis
                            && task.descriptor().workerGroupId().equals(
                                    itemEvidence.workerGroupId()
                            )
                            ? itemEvidence.matchedWorkerIds().stream()
                                    .filter(workerId ->
                                            !roundWorkerIds.contains(workerId))
                                    .toList()
                            : List.of()
            );
            if (itemEvidence != null) {
                holdUntilByMessageId.put(
                        messageId,
                        itemEvidence.holdUntilMillis()
                );
            }
        }
        acquired = candidateSelection.selectHeldCandidates(
                task.descriptor().workerGroupId(),
                requests,
                matched,
                holdUntilByMessageId,
                WorkerCandidateSelectionPolicy.MAX_UNIQUE_WORKERS_PER_ROUND
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
        List<String> missingEvidence = messageIds.stream()
                .filter(messageId -> !result.containsKey(messageId))
                .toList();
        if (!missingEvidence.isEmpty()) {
            Map<String, Long> observed = candidateSelection
                    .observeDueCandidates(task.descriptor().workerGroupId());
            if (!observed.isEmpty()) {
                Map<String, Long> held = candidateSelection
                        .holdObservedCandidates(
                            task.descriptor().workerGroupId(),
                            observed,
                            leaseUntilMillis
                        );
                if (!held.isEmpty()) {
                    List<String> workerIds = List.copyOf(held.keySet());
                    List<ItemRuleMatchDemand> demands = missingEvidence.stream()
                            .map(messageId -> new ItemRuleMatchDemand(
                                    new ItemMatchKey(task.taskId(), messageId),
                                    task.descriptor().workerGroupId(),
                                    workerIds,
                                    leaseUntilMillis
                            ))
                            .toList();
                    workerMatches.offerItemDemands(demands);
                }
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
