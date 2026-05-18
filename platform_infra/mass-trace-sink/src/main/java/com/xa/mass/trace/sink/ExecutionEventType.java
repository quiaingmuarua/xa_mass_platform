package com.xa.mass.trace.sink;

public enum ExecutionEventType {

    TASK_STATUS_TRANSITION(EventCategory.TASK, EventSeverity.INFO),
    TASK_TERMINAL_CLOSED(EventCategory.TASK, EventSeverity.INFO),
    TASK_PROGRESS_SNAPSHOT(EventCategory.TASK, EventSeverity.INFO),
    TASK_WORK_STATUS_TRANSITION(EventCategory.MSG, EventSeverity.INFO),
    TASK_WORK_ATTEMPT_STATUS_TRANSITION(EventCategory.MSG, EventSeverity.INFO),
    TASK_WORK_ATTEMPT_CLOSED(EventCategory.MSG, EventSeverity.INFO),
    TASK_WORK_LOGICALLY_FINAL(EventCategory.MSG, EventSeverity.INFO),
    TASK_WORK_RETRY_RESET(EventCategory.MSG, EventSeverity.WARN),
    WORKER_CONTEXT_STATUS_TRANSITION(EventCategory.WORKER, EventSeverity.INFO),
    WORKER_LOCK_ACQUIRED(EventCategory.WORKER, EventSeverity.INFO),
    WORKER_LOCK_RELEASED(EventCategory.WORKER, EventSeverity.INFO),
    WORKER_MATCH_ACCEPTED(EventCategory.WORKER, EventSeverity.INFO),
    WORKER_MATCH_REJECTED(EventCategory.WORKER, EventSeverity.INFO),
    DISPATCH_REQUESTED(EventCategory.DISPATCH, EventSeverity.INFO),
    DISPATCH_SKIPPED(EventCategory.DISPATCH, EventSeverity.INFO),
    ASSIGNMENT_SUMMARY(EventCategory.ASSIGNMENT, EventSeverity.INFO),
    TASK_STATE_VALIDATION_SUMMARY(EventCategory.VALIDATION, EventSeverity.WARN),
    DISPATCH_BINDING_SUMMARY(EventCategory.ASSIGNMENT, EventSeverity.INFO),
    ASSIGNMENT_QUEUE_SNAPSHOT(EventCategory.ASSIGNMENT, EventSeverity.INFO),
    ASSIGNMENT_RETRY_SCHEDULED(EventCategory.ASSIGNMENT, EventSeverity.WARN),
    CALLBACK_ACCEPTED(EventCategory.CALLBACK, EventSeverity.INFO),
    CALLBACK_IGNORED_DUPLICATE(EventCategory.CALLBACK, EventSeverity.INFO),
    CALLBACK_IGNORED_LATE(EventCategory.CALLBACK, EventSeverity.INFO),
    CALLBACK_REJECTED_NO_ACTIVE_LEASE(EventCategory.CALLBACK, EventSeverity.WARN),
    CALLBACK_REJECTED_NO_ACTIVE_ATTEMPT(EventCategory.CALLBACK, EventSeverity.WARN),
    CALLBACK_REJECTED_INVALID_STATE(EventCategory.CALLBACK, EventSeverity.WARN),
    RESOURCE_RELEASED(EventCategory.RESOURCE, EventSeverity.INFO),
    RESOURCE_RELEASE_FAILED(EventCategory.RESOURCE, EventSeverity.WARN),
    LEASE_EXPIRED(EventCategory.LEASE, EventSeverity.WARN),
    WORKER_COMMAND_STATUS_TRANSITION(EventCategory.WORKER, EventSeverity.INFO),
    WORKER_CAPABILITY_REPORT_APPLIED(EventCategory.WORKER, EventSeverity.INFO),
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

