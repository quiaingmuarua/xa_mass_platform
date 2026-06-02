package com.xa.mass.sdk.event;

import java.util.Objects;

/**
 * Stable event code value object used by SDK control-plane APIs.
 */
public final class EventCode {

    private final String value;

    private EventCode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("event code must not be blank");
        }
        this.value = value.trim();
    }

    public static EventCode of(String value) {
        return new EventCode(value);
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EventCode eventCode)) return false;
        return Objects.equals(value, eventCode.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
}
