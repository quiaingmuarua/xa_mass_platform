package com.xa.mass.client.worker;

public record ResultCorrelationRef(String value) {

    public ResultCorrelationRef {
        value = requireText(value, "resultCorrelationRef");
    }

    public static ResultCorrelationRef of(String value) {
        return new ResultCorrelationRef(value);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }
}
