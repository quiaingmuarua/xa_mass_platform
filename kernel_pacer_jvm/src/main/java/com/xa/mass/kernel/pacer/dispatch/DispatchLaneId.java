package com.xa.mass.kernel.pacer.dispatch;

enum DispatchLaneId {
    TASK_INITIALIZATION,
    WORKER_ALLOCATION,
    TASK_DISPATCH,
    WORKER_SERVICEABILITY;

    boolean consumesInitialTasks() {
        return this == TASK_INITIALIZATION;
    }
}
