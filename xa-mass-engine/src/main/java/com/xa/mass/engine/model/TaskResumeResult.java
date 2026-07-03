package com.xa.mass.engine.model;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;

/**
 * 显式表达恢复任务后的真实结果，避免 boolean 掩盖 PAUSED->READY 与 PAUSED->TERMINAL 分叉。
 */
public class TaskResumeResult {
    public enum Outcome {
        RESUMED_TO_READY,
        COMPLETED_TO_TERMINAL,
        REJECTED
    }

    private final boolean success;
    private final Outcome outcome;
    private final TaskStatus status;
    private final TaskTerminalReason terminalReason;

    private TaskResumeResult(boolean success, Outcome outcome, TaskStatus status, TaskTerminalReason terminalReason) {
        this.success = success;
        this.outcome = outcome;
        this.status = status;
        this.terminalReason = terminalReason;
    }

    public static TaskResumeResult resumedToReady() {
        return new TaskResumeResult(true, Outcome.RESUMED_TO_READY, TaskStatus.READY, null);
    }

    public static TaskResumeResult completedToTerminal(TaskTerminalReason terminalReason) {
        return new TaskResumeResult(true, Outcome.COMPLETED_TO_TERMINAL, TaskStatus.TERMINAL, terminalReason);
    }

    public static TaskResumeResult rejected() {
        return new TaskResumeResult(false, Outcome.REJECTED, null, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public TaskTerminalReason getTerminalReason() {
        return terminalReason;
    }
}
