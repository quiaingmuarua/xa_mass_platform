package com.xa.mass.base.enums.worker;

/**
 * Worker 状态枚举
 * 仅反映基础物理与环境资源状态，不涉及调度与分配
 */
public enum WorkerStatus {
    ONLINE("在线"),
    OFFLINE("离线"),
    EXPIRED("已过期");

    private final String description;

    WorkerStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public boolean isAvailable() {
        return this == ONLINE;
    }

    public boolean isUnavailable() {
        return this == OFFLINE || this == EXPIRED;
    }

    /**
     * Worker model state transitions are intentionally simple:
     * online truth is physical/connectivity truth only, while lock truth lives elsewhere.
     */
    public boolean canTransitionTo(WorkerStatus targetStatus) {
        if (targetStatus == null || this == targetStatus) {
            return false;
        }

        return switch (this) {
            case ONLINE -> targetStatus == OFFLINE || targetStatus == EXPIRED;
            case OFFLINE -> targetStatus == ONLINE || targetStatus == EXPIRED;
            case EXPIRED -> targetStatus == ONLINE || targetStatus == OFFLINE;
        };
    }
}
