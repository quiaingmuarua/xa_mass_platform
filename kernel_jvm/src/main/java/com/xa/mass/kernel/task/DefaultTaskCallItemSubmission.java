package com.xa.mass.kernel.task;

import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreTransitionResult;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreTransitionStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendStatus;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class DefaultTaskCallItemSubmission
        implements TaskCallItemSubmission {

    private final TaskScoreBandCore taskScore;
    private final TaskRuntime taskRuntime;

    public DefaultTaskCallItemSubmission(
            TaskScoreBandCore taskScore,
            TaskRuntime taskRuntime
    ) {
        this.taskScore = Objects.requireNonNull(taskScore, "taskScore");
        this.taskRuntime = Objects.requireNonNull(
                taskRuntime,
                "taskRuntime"
        );
    }

    @Override
    public TaskCallSubmissionResult submit(
            String taskId,
            List<TaskItem> items
    ) {
        TaskCallSubmissionResult invalid = validate(taskId, items);
        if (invalid != null) {
            return invalid;
        }

        TaskScoreTransitionResult before;
        try {
            before = taskScore.tryReleaseIdlePark(taskId);
        } catch (RuntimeException error) {
            return result(
                    TaskCallSubmissionStatus.RETRYABLE,
                    "Task Call submission owner is unavailable"
            );
        }
        if (!accepted(before.status())) {
            return transitionFailure(before);
        }

        Map<String, TaskItemAppendResult> itemResults;
        try {
            Map<String, TaskItemAppendResult> appended =
                    taskRuntime.appendItems(taskId, items);
            itemResults = orderedResults(items, appended);
        } catch (RuntimeException error) {
            return result(
                    TaskCallSubmissionStatus.RETRYABLE,
                    "Task Call submission owner is unavailable"
            );
        }

        TaskScoreTransitionResult after;
        try {
            after = taskScore.tryReleaseIdlePark(taskId);
        } catch (RuntimeException error) {
            after = null;
        }
        if (after == null || !accepted(after.status())) {
            return new TaskCallSubmissionResult(
                    TaskCallSubmissionStatus.RETRYABLE,
                    itemResults,
                    "TaskItems were stored but Task activation was not "
                            + "confirmed"
            );
        }
        return new TaskCallSubmissionResult(
                TaskCallSubmissionStatus.SUBMITTED,
                itemResults,
                null
        );
    }

    private static TaskCallSubmissionResult validate(
            String taskId,
            List<TaskItem> items
    ) {
        if (taskId == null || taskId.isBlank()) {
            return result(
                    TaskCallSubmissionStatus.INVALID,
                    "taskId must be non-blank"
            );
        }
        if (items == null || items.isEmpty() || items.size() > MAX_ITEMS) {
            return result(
                    TaskCallSubmissionStatus.INVALID,
                    "Task Call submission requires 1..100 Items"
            );
        }
        Set<String> messageIds = new HashSet<>();
        for (TaskItem item : items) {
            if (item == null || !messageIds.add(item.messageId())) {
                return result(
                        TaskCallSubmissionStatus.INVALID,
                        "Task Call message ids must be unique"
                );
            }
        }
        return null;
    }

    private static Map<String, TaskItemAppendResult> orderedResults(
            List<TaskItem> items,
            Map<String, TaskItemAppendResult> appended
    ) {
        Map<String, TaskItemAppendResult> results = new LinkedHashMap<>();
        for (TaskItem item : items) {
            results.put(
                    item.messageId(),
                    appended.getOrDefault(
                            item.messageId(),
                            new TaskItemAppendResult(
                                    TaskItemAppendStatus.RETRYABLE,
                                    "Task Runtime omitted the Item result"
                            )
                    )
            );
        }
        return results;
    }

    private static boolean accepted(TaskScoreTransitionStatus status) {
        return status == TaskScoreTransitionStatus.TRANSITIONED
                || status == TaskScoreTransitionStatus.NOOP;
    }

    private static TaskCallSubmissionResult transitionFailure(
            TaskScoreTransitionResult transition
    ) {
        TaskCallSubmissionStatus status = switch (transition.status()) {
            case STALE -> TaskCallSubmissionStatus.STALE;
            case INVALID -> TaskCallSubmissionStatus.INVALID;
            case TRANSITIONED, NOOP -> TaskCallSubmissionStatus.RETRYABLE;
        };
        return result(status, null);
    }

    private static TaskCallSubmissionResult result(
            TaskCallSubmissionStatus status,
            String reason
    ) {
        return new TaskCallSubmissionResult(status, Map.of(), reason);
    }
}
