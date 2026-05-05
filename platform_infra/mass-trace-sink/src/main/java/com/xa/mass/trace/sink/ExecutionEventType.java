package com.xa.mass.trace.sink;

public enum ExecutionEventType {

    TASK_STATUS_CHANGED(EventCategory.TASK, EventSeverity.INFO),
    MSG_STATUS_CHANGED(EventCategory.MSG, EventSeverity.INFO),
    MSG_DISPATCH_SENT(EventCategory.DISPATCH, EventSeverity.INFO),
    MSG_RETRY_SCHEDULED(EventCategory.MSG, EventSeverity.WARN),
    LEASE_EXPIRED(EventCategory.LEASE, EventSeverity.WARN),
    WORKER_ONLINE(EventCategory.WORKER, EventSeverity.INFO),
    WORKER_OFFLINE(EventCategory.WORKER, EventSeverity.WARN);

    private final EventCategory category;
    private final EventSeverity severity;

    ExecutionEventType(EventCategory category, EventSeverity severity) {
        this.category = category;
        this.severity = severity;
    }

    public EventCategory defaultCategory() {
        return category;
    }

    public EventSeverity defaultSeverity() {
        return severity;
    }
}
