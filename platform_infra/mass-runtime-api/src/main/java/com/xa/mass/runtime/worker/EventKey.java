package com.xa.mass.runtime.worker;

import java.util.Objects;

/**
 * WorkerGroup candidate-source capability key.
 */
public record EventKey(String projectCode, String eventCode) {

    public EventKey {
        projectCode = requireNonBlank(projectCode, "projectCode");
        eventCode = requireNonBlank(eventCode, "eventCode");
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return Objects.requireNonNull(value, fieldName).trim();
    }
}
