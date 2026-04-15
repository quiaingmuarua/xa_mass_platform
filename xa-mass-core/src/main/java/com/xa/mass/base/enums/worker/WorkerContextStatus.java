package com.xa.mass.base.enums.worker;

/**
 * WorkerContext 状态枚举
 * 记录 worker 上下文（凭据/能力载体）的占用状态
 * 状态流转：IDLE -> RESERVED -> OCCUPIED -> IDLE
 * 或者：IDLE -> BLOCKED -> IDLE
 * 或者：任意状态 -> INVALID
 */
public enum WorkerContextStatus {
    IDLE("空闲可用"),
    RESERVED("已预留"),
    OCCUPIED("执行中"),
    BLOCKED("已锁定"),
    INVALID("已失效");

    private final String description;

    WorkerContextStatus(String description) {
        this.description = description;
    }

    public String getDescription() { return description; }

    public boolean isAllocatable() { return this == IDLE; }
    public boolean isInUse()       { return this == RESERVED || this == OCCUPIED; }
    public boolean isAvailable()   { return this == IDLE || this == RESERVED; }
    public boolean isFinal()       { return this == INVALID; }

    public boolean canTransitionTo(WorkerContextStatus targetStatus) {
        if (targetStatus == INVALID) return true;
        switch (this) {
            case IDLE:     return targetStatus == RESERVED || targetStatus == BLOCKED;
            case RESERVED: return targetStatus == OCCUPIED || targetStatus == IDLE || targetStatus == BLOCKED;
            case OCCUPIED: return targetStatus == IDLE || targetStatus == BLOCKED;
            case BLOCKED:  return targetStatus == IDLE;
            case INVALID:  return false;
            default:       return false;
        }
    }
}
