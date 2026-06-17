package com.xa.mass.client.worker;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.xa.mass.client.payload.MassPayload;

import java.util.Map;
import java.util.Objects;

public final class WorkerInvocation {
    private final String resultCorrelationRef;
    private final String eventCode;
    private final Map<String, Object> input;
    private final Map<String, Object> sharedConfig;

    @JsonCreator
    public WorkerInvocation(@JsonProperty("resultCorrelationRef") String resultCorrelationRef,
                            @JsonProperty("eventCode") String eventCode,
                            @JsonProperty("input") Map<String, Object> input,
                            @JsonProperty("sharedConfig") Map<String, Object> sharedConfig) {
        this.resultCorrelationRef = requireText(resultCorrelationRef, "resultCorrelationRef");
        this.eventCode = requireText(eventCode, "eventCode");
        this.input = WorkerRequestSupport.copyObjectMap(input);
        this.sharedConfig = WorkerRequestSupport.copyObjectMap(sharedConfig);
    }

    public static WorkerInvocation of(String resultCorrelationRef,
                                      String eventCode,
                                      MassPayload input,
                                      MassPayload sharedConfig) {
        return new WorkerInvocation(
                resultCorrelationRef,
                eventCode,
                input == null ? Map.of() : input.asMap(),
                sharedConfig == null ? Map.of() : sharedConfig.asMap()
        );
    }

    public String resultCorrelationRef() {
        return resultCorrelationRef;
    }

    public String eventCode() {
        return eventCode;
    }

    public MassPayload input() {
        return MassPayload.of(input);
    }

    public MassPayload sharedConfig() {
        return MassPayload.of(sharedConfig);
    }

    public Map<String, Object> inputMap() {
        return Map.copyOf(input);
    }

    public Map<String, Object> sharedConfigMap() {
        return Map.copyOf(sharedConfig);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) {
            return true;
        }
        if (!(candidate instanceof WorkerInvocation that)) {
            return false;
        }
        return resultCorrelationRef.equals(that.resultCorrelationRef)
                && eventCode.equals(that.eventCode)
                && input.equals(that.input)
                && sharedConfig.equals(that.sharedConfig);
    }

    @Override
    public int hashCode() {
        return Objects.hash(resultCorrelationRef, eventCode, input, sharedConfig);
    }

    @Override
    public String toString() {
        return "WorkerInvocation{"
                + "resultCorrelationRef='" + resultCorrelationRef + '\''
                + ", eventCode='" + eventCode + '\''
                + ", input=" + input
                + ", sharedConfig=" + sharedConfig
                + '}';
    }
}
