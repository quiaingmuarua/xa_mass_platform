package com.xa.mass.sdk.worker;

import com.xa.mass.transport.payload.TransportJsonValueNormalizer;

import java.util.Map;
import java.util.Objects;

/**
 * SDK/server public worker polling DTO.
 */
public final class PulledTaskDispatch {

    private final String resultCorrelationRef;
    private final String eventCode;
    private final Map<String, Object> input;
    private final Map<String, Object> sharedConfig;

    public PulledTaskDispatch(String resultCorrelationRef,
                              String eventCode,
                              Map<String, Object> input,
                              Map<String, Object> sharedConfig) {
        this.resultCorrelationRef = requireText(resultCorrelationRef, "resultCorrelationRef");
        this.eventCode = optionalText(eventCode);
        this.input = TransportJsonValueNormalizer.normalizeObject(input, "input");
        this.sharedConfig = TransportJsonValueNormalizer.normalizeObject(sharedConfig, "sharedConfig");
    }

    public String getResultCorrelationRef() {
        return resultCorrelationRef;
    }

    public String getEventCode() {
        return eventCode;
    }

    public Map<String, Object> getInput() {
        return input;
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

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PulledTaskDispatch that)) {
            return false;
        }
        return Objects.equals(resultCorrelationRef, that.resultCorrelationRef)
                && Objects.equals(eventCode, that.eventCode)
                && Objects.equals(input, that.input)
                && Objects.equals(sharedConfig, that.sharedConfig);
    }

    @Override
    public int hashCode() {
        return Objects.hash(resultCorrelationRef, eventCode, input, sharedConfig);
    }

    @Override
    public String toString() {
        return "PulledTaskDispatch{"
                + "resultCorrelationRef='" + resultCorrelationRef + '\''
                + ", eventCode='" + eventCode + '\''
                + '}';
    }
}
