package com.xa.mass.sdk.worker;

import com.xa.mass.transport.payload.TransportJsonValueNormalizer;

import java.util.Map;
import java.util.Objects;

/**
 * SDK/server public worker action DTO.
 */
public final class WorkerAction {

    private final String actionId;
    private final String replyRef;
    private final String eventCode;
    private final String body;
    private final Map<String, Object> sharedConfig;

    public WorkerAction(String actionId,
                        String replyRef,
                        String eventCode,
                        String body,
                        Map<String, Object> sharedConfig) {
        this.actionId = requireText(actionId, "actionId");
        this.replyRef = requireText(replyRef, "replyRef");
        this.eventCode = requireText(eventCode, "eventCode");
        this.body = requireBody(body);
        this.sharedConfig = TransportJsonValueNormalizer.normalizeObject(sharedConfig, "sharedConfig");
    }

    public String getActionId() {
        return actionId;
    }

    public String getReplyRef() {
        return replyRef;
    }

    public String getEventCode() {
        return eventCode;
    }

    public String getBody() {
        return body;
    }

    public Map<String, Object> getSharedConfig() {
        return sharedConfig;
    }

    private static String requireText(String value, String fieldName) {
        String normalized = optionalText(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String optionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String requireBody(String value) {
        if (value == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WorkerAction that)) {
            return false;
        }
        return Objects.equals(actionId, that.actionId)
                && Objects.equals(replyRef, that.replyRef)
                && Objects.equals(eventCode, that.eventCode)
                && Objects.equals(body, that.body)
                && Objects.equals(sharedConfig, that.sharedConfig);
    }

    @Override
    public int hashCode() {
        return Objects.hash(actionId, replyRef, eventCode, body, sharedConfig);
    }

    @Override
    public String toString() {
        return "WorkerAction{"
                + "actionId='" + actionId + '\''
                + ", replyRef='" + replyRef + '\''
                + ", eventCode='" + eventCode + '\''
                + '}';
    }
}
