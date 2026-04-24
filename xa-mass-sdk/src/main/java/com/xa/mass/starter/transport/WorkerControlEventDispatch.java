package com.xa.mass.starter.transport;

import java.util.Objects;

/**
 * Canonical SDK runtime request for sending a control/debug event to a worker
 * through the worker's configured transport binding.
 */
public final class WorkerControlEventDispatch {

    private final String workerId;
    private final String project;
    private final String eventCode;
    private final String requestId;
    private final Object payload;

    public WorkerControlEventDispatch(String workerId,
                                      String project,
                                      String eventCode,
                                      String requestId,
                                      Object payload) {
        this.workerId = requireText(workerId, "workerId");
        this.project = requireText(project, "project");
        this.eventCode = requireText(eventCode, "eventCode");
        this.requestId = requireText(requestId, "requestId");
        this.payload = payload;
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

    public Object getPayload() {
        return payload;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
