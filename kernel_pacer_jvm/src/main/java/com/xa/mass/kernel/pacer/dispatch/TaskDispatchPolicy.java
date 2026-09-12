package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.task.TaskItemWorkerSelector;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreObservation;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.assignment.WorkerCandidateIndex.TaskQuery;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

final class TaskDispatchPolicy {

    static final int PER_TASK_DISPATCH_LIMIT = 100;
    static final long ITEM_CLAIM_LEASE_MILLIS = 5_000;

    private final TaskScoreBandCore taskScores;
    private final TaskItemScoreBandCore itemScores;
    private final TaskRuntime taskRuntime;
    private final TaskAssignmentDispatcher assignmentDispatcher;
    private final TaskIdleSettlement idleSettlement;
    private final WorkerCandidateSelectionPolicy candidateSelection;
    private final LongSupplier currentTimeMillis;
    private final int failedOutcomeTag;

    TaskDispatchPolicy(
            TaskScoreBandCore taskScores,
            TaskItemScoreBandCore itemScores,
            TaskRuntime taskRuntime,
            TaskAssignmentDispatcher assignmentDispatcher,
            TaskIdleSettlement idleSettlement,
            WorkerCandidateSelectionPolicy candidateSelection,
            int failedOutcomeTag
    ) {
        this(
                taskScores,
                itemScores,
                taskRuntime,
                assignmentDispatcher,
                idleSettlement,
                candidateSelection,
                failedOutcomeTag,
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
            int failedOutcomeTag,
            LongSupplier currentTimeMillis
    ) {
        this.taskScores = Objects.requireNonNull(taskScores, "taskScores");
        this.itemScores = Objects.requireNonNull(itemScores, "itemScores");
        this.taskRuntime = Objects.requireNonNull(taskRuntime, "taskRuntime");
        if (failedOutcomeTag < TaskItemScoreBandCore.MIN_TERMINAL_TAG
                || failedOutcomeTag > TaskItemScoreBandCore.MAX_TERMINAL_TAG) {
            throw new IllegalArgumentException("failedOutcomeTag must be in 2..9");
        }
        this.failedOutcomeTag = failedOutcomeTag;
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

    int dispatchTasks(List<ObservedTask> tasks) {
        Objects.requireNonNull(tasks, "tasks");
        Map<String, String> coordinates = new LinkedHashMap<>();
        tasks.forEach(task -> coordinates.put(task.taskId(), task.descriptor().workerGroupId()));
        Map<String, TaskQuery> queries;
        try {
            queries = candidateSelection.prepareQueries(coordinates);
        } catch (RuntimeException failure) {
            System.getLogger(TaskDispatchPolicy.class.getName()).log(System.Logger.Level.WARNING,
                    "operation=dispatch.prepareQueries candidate admission unavailable", failure);
            queries = Map.of();
        }
        long dispatchTimeMillis = currentTimeMillis.getAsLong();
        long claimUntilMillis = Math.addExact(
                dispatchTimeMillis,
                ITEM_CLAIM_LEASE_MILLIS
        );
        Set<String> roundWorkerIds = new LinkedHashSet<>();
        int published = 0;
        for (ObservedTask task : tasks) {
            long checkedAt = DispatchStageEvent.start();
            Map<String, TaskItemScoreObservation> observed =
                    itemScores.acquireItemScoreCandidates(
                            task.taskId(),
                            PER_TASK_DISPATCH_LIMIT
                    );
            DispatchStageEvent.items(checkedAt, "DISPATCH_CHECK", task.taskId(), observed.keySet(), observed.size(), false);
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
                long failureStarted = DispatchStageEvent.start();
                taskRuntime.storeTaskItemFailedResults(
                        task.taskId(),
                        failedIds
                );
                DispatchStageEvent.items(failureStarted, "FAILED_RESULT_STORED", task.taskId(), failedIds, failedIds.size(), false);
                Map<String, TaskItemScoreBandCore.TaskItemOutcomeTarget> targets = new LinkedHashMap<>();
                failedIds.forEach(id -> targets.put(id,
                        new TaskItemScoreBandCore.TaskItemOutcomeTarget(failedOutcomeTag, dispatchTimeMillis)));
                itemScores.promoteItemOutcomes(task.taskId(), targets);
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
                long selectedAt = DispatchStageEvent.start();
                Map<String, HeldWorkerCandidate> assignments =
                        assignments(
                                task,
                                queries.get(task.taskId()),
                                claimableIds,
                                items,
                                claimUntilMillis,
                                roundWorkerIds
                        );
                DispatchStageEvent.items(selectedAt, "CANDIDATES", task.taskId(), claimableIds, assignments.size(), false);
                List<TaskAssignmentDispatcher.AssignmentAttempt> attempts =
                        new ArrayList<>(assignments.size());
                assignments.forEach((messageId, worker) -> attempts.add(
                        new TaskAssignmentDispatcher.AssignmentAttempt(
                                Objects.requireNonNull(
                                        items.get(messageId),
                                        "assigned TaskItem"
                                ),
                                Objects.requireNonNull(
                                        observed.get(messageId),
                                        "assigned TaskItem score"
                                ).score(),
                                worker
                        )
                ));
                published += assignmentDispatcher.dispatch(
                        task,
                        attempts,
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

    private Map<String, HeldWorkerCandidate> assignments(
            ObservedTask task,
            TaskQuery query,
            List<String> messageIds,
            Map<String, TaskItem> items,
            long leaseUntilMillis,
            Set<String> roundWorkerIds
    ) {
        var selectors = new LinkedHashMap<String, TaskItemWorkerSelector>();
        for (String messageId : messageIds) {
            selectors.put(messageId, Objects.requireNonNull(
                    items.get(messageId), "claimable TaskItem").workerSelector());
        }
        return candidateSelection.acquireCandidates(
                query,
                task.descriptor().workerGroupId(),
                selectors,
                roundWorkerIds,
                leaseUntilMillis
        );
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
