package com.xa.mass.kernel.pacer.result;

enum ResultLaneId {
    TASK_SUCCESS(0),
    TASK_FAILURE(1),
    ADAPTER_EVIDENCE(2);

    private final int priority;

    ResultLaneId(int priority) {
        this.priority = priority;
    }

    int priority() {
        return priority;
    }
}
