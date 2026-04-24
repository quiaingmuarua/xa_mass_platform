package com.xa.mass.starter.transport;

/**
 * Result metadata returned after a worker control/debug event is accepted by a
 * concrete transport binding for delivery.
 */
public final class WorkerControlEventPublishResult {

    private final String messageId;
    private final String workerId;
    private final String project;
    private final String eventCode;
    private final String requestId;

    public WorkerControlEventPublishResult(String messageId,
                                           String workerId,
                                           String project,
                                           String eventCode,
                                           String requestId) {
        this.messageId = messageId;
        this.workerId = workerId;
        this.project = project;
        this.eventCode = eventCode;
        this.requestId = requestId;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getProject() {
        return project;
    }

    public String getEventCode() {
        return eventCode;
    }

    public String getRequestId() {
        return requestId;
    }
}
