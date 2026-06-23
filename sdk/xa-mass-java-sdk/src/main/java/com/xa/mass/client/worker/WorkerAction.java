package com.xa.mass.client.worker;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.xa.mass.client.payload.MassPayload;

import java.util.Map;
import java.util.Objects;

public final class WorkerAction {
    private final String actionId;
    private final String replyRef;
    private final String eventCode;
    private final String body;
    private final Map<String, Object> sharedConfig;

    @JsonCreator
    public WorkerAction(@JsonProperty("actionId") String actionId,
                        @JsonProperty("replyRef") String replyRef,
                        @JsonProperty("eventCode") String eventCode,
                        @JsonProperty("body") String body,
                        @JsonProperty("sharedConfig") Map<String, Object> sharedConfig) {
        this.actionId = requireText(actionId, "actionId");
        this.replyRef = requireText(replyRef, "replyRef");
        this.eventCode = requireText(eventCode, "eventCode");
        this.body = requireBody(body);
        this.sharedConfig = WorkerRequestSupport.copyObjectMap(sharedConfig);
    }

    public static WorkerAction of(String actionId,
                                  String replyRef,
                                  String eventCode,
                                  String body,
                                  MassPayload sharedConfig) {
        return new WorkerAction(
                actionId,
                replyRef,
                eventCode,
                body,
                sharedConfig == null ? Map.of() : sharedConfig.asMap()
        );
    }

    public String actionId() {
        return actionId;
    }

    public String replyRef() {
        return replyRef;
    }

    public String eventCode() {
        return eventCode;
    }

    public String body() {
        return body;
    }

    public MassPayload sharedConfig() {
        return MassPayload.of(sharedConfig);
    }

    public Map<String, Object> sharedConfigValues() {
        return Map.copyOf(sharedConfig);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    private static String requireBody(String value) {
        if (value == null) {
            throw new IllegalArgumentException("body is required");
        }
        return value;
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) {
            return true;
        }
        if (!(candidate instanceof WorkerAction that)) {
            return false;
        }
        return actionId.equals(that.actionId)
                && replyRef.equals(that.replyRef)
                && eventCode.equals(that.eventCode)
                && body.equals(that.body)
                && sharedConfig.equals(that.sharedConfig);
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
                + ", sharedConfig=" + sharedConfig
                + '}';
    }
}
