package com.xa.mass.starter.transport;

import java.util.Map;

/**
 * Canonical SDK runtime request for sending a control/debug event to a worker
 * through the worker's configured transport binding.
 */
public final class WorkerControlEventDispatch {

    private final String workerId;
    private final String project;
    private final String eventCode;
    private final String requestId;
    private final Map<String, String> headers;
    private final Object payload;
    private final String clientId;
    private final String userId;

    public WorkerControlEventDispatch(String workerId,
                                      String project,
                                      String eventCode,
                                      String requestId,
                                      Map<String, String> headers,
                                      Object payload,
                                      String clientId,
                                      String userId) {
        this.workerId = requireText(workerId, "workerId");
        this.project = requireText(project, "project");
        this.eventCode = requireText(eventCode, "eventCode");
        this.requestId = requireText(requestId, "requestId");
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.payload = payload;
        this.clientId = normalizeNullable(clientId);
        this.userId = normalizeNullable(userId);
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

    public Map<String, String> getHeaders() {
        return headers;
    }

    public Object getPayload() {
        return payload;
    }

    public String getClientId() {
        return clientId;
    }

    public String getUserId() {
        return userId;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
