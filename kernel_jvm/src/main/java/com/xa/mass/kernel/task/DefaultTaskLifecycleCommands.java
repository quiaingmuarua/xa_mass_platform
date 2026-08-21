package com.xa.mass.kernel.task;

import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreState;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreTransitionStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import java.util.List;
import java.util.Objects;

public final class DefaultTaskLifecycleCommands
        implements TaskLifecycleCommands {

    private final TaskScoreBandCore taskScore;
    private final TaskResourceCatalog taskCatalog;

    public DefaultTaskLifecycleCommands(
            TaskScoreBandCore taskScore,
            TaskResourceCatalog taskCatalog
    ) {
        this.taskScore = Objects.requireNonNull(taskScore, "taskScore");
        this.taskCatalog = Objects.requireNonNull(
                taskCatalog,
                "taskCatalog"
        );
    }

    @Override
    public TaskApprovalResult approveTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return new TaskApprovalResult(
                    TaskApprovalStatus.INVALID,
                    "task id is required"
            );
        }

        TaskDescriptor descriptor;
        TaskScoreState state;
        try {
            descriptor = taskCatalog.loadTaskAllocationDescriptors(
                    List.of(taskId)
            ).get(taskId);
            if (descriptor == null) {
                return new TaskApprovalResult(
                        TaskApprovalStatus.NOT_FOUND
                );
            }
            state = taskScore.getScoreStates(List.of(taskId)).get(taskId);
        } catch (RuntimeException error) {
            return retryableApproval();
        }

        TaskApprovalResult classified = classify(state);
        if (classified != null) {
            return classified;
        }
        long stateTimeMillis = Objects.requireNonNull(
                state.timeMillis(),
                "PRE_REVIEW timeMillis"
        );
        int priority = Integer.parseInt(
                descriptor.config().get("priority")
        );

        try {
            var transition = taskScore.rewriteScore(
                    taskId,
                    TaskScoreBand.PRE_REVIEW,
                    Math.max(
                            System.currentTimeMillis(),
                            Math.addExact(
                                    stateTimeMillis,
                                    TaskScoreBandCore.SLOT_MILLIS
                            )
                    ),
                    TaskScoreBand.ADMISSION_VISIBLE,
                    priority
            );
            if (transition.status()
                    == TaskScoreTransitionStatus.TRANSITIONED) {
                return new TaskApprovalResult(
                        TaskApprovalStatus.APPROVED
                );
            }
            if (transition.status()
                    == TaskScoreTransitionStatus.INVALID) {
                return new TaskApprovalResult(
                        TaskApprovalStatus.INVALID,
                        "task approval transition was rejected"
                );
            }
            TaskScoreState current = taskScore.getScoreStates(
                    List.of(taskId)
            ).get(taskId);
            TaskApprovalResult reclassified = classify(current);
            return reclassified == null
                    ? new TaskApprovalResult(
                            TaskApprovalStatus.RETRYABLE,
                            "task score changed during approval"
                    )
                    : reclassified;
        } catch (RuntimeException error) {
            return retryableApproval();
        }
    }

    @Override
    public TaskCloseResult closeTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return new TaskCloseResult(
                    TaskCloseStatus.INVALID,
                    "task id is required"
            );
        }
        try {
            TaskScoreState state = taskScore.getScoreStates(
                    List.of(taskId)
            ).get(taskId);
            if (state == null) {
                return new TaskCloseResult(TaskCloseStatus.NOT_FOUND);
            }
            if (state.band() == TaskScoreBand.TERMINAL) {
                return new TaskCloseResult(
                        TaskCloseStatus.ALREADY_CLOSED
                );
            }

            var transition = taskScore.closeScore(
                    taskId,
                    TaskScoreBandCore.TERMINAL_SCORE_MAX
            );
            return switch (transition.status()) {
                case TRANSITIONED -> new TaskCloseResult(
                        TaskCloseStatus.CLOSED
                );
                case NOOP -> new TaskCloseResult(
                        TaskCloseStatus.ALREADY_CLOSED
                );
                case INVALID -> new TaskCloseResult(
                        TaskCloseStatus.INVALID,
                        "task close transition was rejected"
                );
                case STALE -> new TaskCloseResult(
                        TaskCloseStatus.RETRYABLE,
                        "task score changed during close"
                );
            };
        } catch (RuntimeException error) {
            return new TaskCloseResult(
                    TaskCloseStatus.RETRYABLE,
                    "Task lifecycle owner is unavailable"
            );
        }
    }

    private static TaskApprovalResult classify(TaskScoreState state) {
        if (state == null) {
            return new TaskApprovalResult(TaskApprovalStatus.NOT_FOUND);
        }
        if (state.band() == TaskScoreBand.ADMISSION_VISIBLE
                || state.band() == TaskScoreBand.RUNNING_VISIBLE) {
            return new TaskApprovalResult(
                    TaskApprovalStatus.ALREADY_APPROVED
            );
        }
        if (state.band() == TaskScoreBand.TERMINAL) {
            return new TaskApprovalResult(
                    TaskApprovalStatus.CONFLICT,
                    "terminal task cannot be approved"
            );
        }
        if (state.band() != TaskScoreBand.PRE_REVIEW
                || state.timeMillis() == null) {
            return new TaskApprovalResult(
                    TaskApprovalStatus.CONFLICT,
                    "task is not in an approvable score state"
            );
        }
        return null;
    }

    private static TaskApprovalResult retryableApproval() {
        return new TaskApprovalResult(
                TaskApprovalStatus.RETRYABLE,
                "Task lifecycle owner is unavailable"
        );
    }
}
