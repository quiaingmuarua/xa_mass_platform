package com.xa.mass.starter.config;

import com.xa.mass.base.enums.task.TaskHoldReason;
import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.engine.TaskCommandPort;
import com.xa.mass.engine.model.TaskAppendOutcome;
import com.xa.mass.engine.model.TaskCommandOutcome;
import com.xa.mass.engine.model.TaskDefinitionPatch;

import java.util.List;
import java.util.Map;
import java.util.Objects;

final class TaskReadViewPublishingTaskCommandPort implements TaskCommandPort {

    private final TaskCommandPort delegate;
    private final TaskReadViewProjectionStore projection;

    TaskReadViewPublishingTaskCommandPort(TaskCommandPort delegate, TaskReadViewProjectionStore projection) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.projection = Objects.requireNonNull(projection, "projection");
    }

    @Override
    public TaskCommandOutcome createTaskShell(TaskShellCreateRequestDto dto) {
        TaskCommandOutcome outcome = delegate.createTaskShell(dto);
        if (accepted(outcome)) {
            projection.recordCreated(outcome.taskId(), dto);
        }
        return outcome;
    }

    @Override
    public TaskCommandOutcome patchTaskDefinition(String taskId, TaskDefinitionPatch patch) {
        TaskCommandOutcome outcome = delegate.patchTaskDefinition(taskId, patch);
        if (accepted(outcome)) {
            projection.recordPatched(outcome.taskId(), patch);
        }
        return outcome;
    }

    @Override
    public TaskCommandOutcome deleteTask(String taskId) {
        TaskCommandOutcome outcome = delegate.deleteTask(taskId);
        if (accepted(outcome)) {
            projection.remove(outcome.taskId());
        }
        return outcome;
    }

    @Override
    public TaskCommandOutcome approveTask(String taskId) {
        TaskCommandOutcome outcome = delegate.approveTask(taskId);
        if (applied(outcome)) {
            projection.recordStatus(outcome.taskId(), TaskStatus.READY, null);
        }
        return outcome;
    }

    @Override
    public TaskCommandOutcome rejectTask(String taskId) {
        TaskCommandOutcome outcome = delegate.rejectTask(taskId);
        if (accepted(outcome)) {
            projection.recordStatus(outcome.taskId(), TaskStatus.BLOCKED, TaskHoldReason.REVIEW_REJECTED);
        }
        return outcome;
    }

    @Override
    public TaskCommandOutcome blockTask(String taskId) {
        TaskCommandOutcome outcome = delegate.blockTask(taskId);
        if (accepted(outcome)) {
            projection.recordStatus(outcome.taskId(), TaskStatus.BLOCKED, TaskHoldReason.MANUAL_BLOCKED);
        }
        return outcome;
    }

    @Override
    public TaskCommandOutcome pauseTask(String taskId) {
        TaskCommandOutcome outcome = delegate.pauseTask(taskId);
        if (accepted(outcome)) {
            projection.recordStatus(outcome.taskId(), TaskStatus.PAUSED, null);
        }
        return outcome;
    }

    @Override
    public TaskCommandOutcome resumeTask(String taskId) {
        TaskCommandOutcome outcome = delegate.resumeTask(taskId);
        if (applied(outcome)) {
            if ("TASK_RESUMED_TO_TERMINAL".equals(outcome.reasonCode())) {
                projection.recordTerminal(outcome.taskId(), null);
            } else {
                projection.recordStatus(outcome.taskId(), TaskStatus.READY, null);
            }
        }
        return outcome;
    }

    @Override
    public TaskCommandOutcome cancelTask(String taskId) {
        TaskCommandOutcome outcome = delegate.cancelTask(taskId);
        if (applied(outcome)) {
            projection.recordTerminal(outcome.taskId(), TaskTerminalReason.MANUAL_CANCELLED);
        }
        return outcome;
    }

    @Override
    public TaskCommandOutcome terminateTask(String taskId, TaskTerminalReason reason) {
        TaskCommandOutcome outcome = delegate.terminateTask(taskId, reason);
        if (applied(outcome)) {
            projection.recordTerminal(outcome.taskId(), reason);
        }
        return outcome;
    }

    @Override
    public TaskAppendOutcome appendTaskItems(String taskId, List<Map<String, Object>> items) {
        TaskAppendOutcome outcome = delegate.appendTaskItems(taskId, items);
        if (outcome != null && outcome.accepted()) {
            projection.recordAppend(outcome.taskId(), outcome.acceptedCount());
        }
        return outcome;
    }

    @Override
    public TaskCommandOutcome sealTask(String taskId) {
        TaskCommandOutcome outcome = delegate.sealTask(taskId);
        if (accepted(outcome)) {
            projection.recordIntake(outcome.taskId(), TaskIntakeStatus.SEALED);
        }
        return outcome;
    }

    private static boolean accepted(TaskCommandOutcome outcome) {
        return outcome != null && outcome.accepted();
    }

    private static boolean applied(TaskCommandOutcome outcome) {
        return outcome != null && outcome.applied();
    }
}
