package com.xa.mass.base.enums.task;

/**
 * 任务进入 TERMINAL 的业务原因。
 */
public enum TaskTerminalReason {
    MANUAL_CANCELLED,
    ALL_MESSAGES_SUCCEEDED,
    ALL_MESSAGES_FAILED,
    MIXED_MESSAGE_RESULTS,
    MAX_RUNTIME_REACHED,
    SUCCESS_RATE_REACHED,
    RETRY_BUDGET_EXHAUSTED
}
