package com.xa.mass.base.enums.taskmsg;

/**
 * Task message lifecycle states.
 * Mainline flow: INIT -> ASSIGNED -> RUNNING -> SUCCESS/FAILED/EXPIRED.
 *
 * <p>This enum models the platform lifecycle contract for a work item. It is
 * not a full transport-event history. Transport-specific phases such as
 * broker queueing, downstream ack, or delivery retries should live in trace or
 * transport-layer data instead of being overloaded into this enum.
 */
public enum TaskMsgStatus {
    INIT("初始"),
    ASSIGNED("已分配"),
    RUNNING("执行中"),
    SUCCESS("成功"),
    FAILED("失败"),
    EXPIRED("已过期");

    private final String description;

    TaskMsgStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public boolean isFinal() {
        return this == SUCCESS || this == FAILED || this == EXPIRED;
    }

    public boolean isProcessing() {
        return this == ASSIGNED || this == RUNNING;
    }

    public boolean isSuccess() {
        return this == SUCCESS;
    }

    public boolean isFailed() {
        return this == FAILED || this == EXPIRED;
    }

    public boolean isRetryable() {
        return this == FAILED || this == EXPIRED;
    }

    public boolean canTransitionTo(TaskMsgStatus targetStatus) {
        switch (this) {
            case INIT:
                return targetStatus == ASSIGNED;
            case ASSIGNED:
                return targetStatus == RUNNING || targetStatus == FAILED || targetStatus == EXPIRED;
            case RUNNING:
                return targetStatus == SUCCESS || targetStatus == FAILED || targetStatus == EXPIRED;
            case SUCCESS:
            case FAILED:
            case EXPIRED:
                return false;
            default:
                return false;
        }
    }
}
