package com.xa.mass.sdk.worker;

import com.xa.mass.transport.payload.TransportJsonValueNormalizer;

import java.util.Map;

/**
 * Embedded SDK worker result submit command.
 */
public record WorkerResultSubmitRequest(String resultCorrelationRef,
                                        boolean success,
                                        String detail,
                                        String errorCode,
                                        Map<String, Object> output) {

    public WorkerResultSubmitRequest {
        resultCorrelationRef = requireText(resultCorrelationRef, "resultCorrelationRef");
        detail = normalize(detail);
        errorCode = normalize(errorCode);
        output = TransportJsonValueNormalizer.normalizeObject(output, "output");
    }

    public static WorkerResultSubmitRequest of(String resultCorrelationRef,
                                               boolean success,
                                               String detail,
                                               String errorCode,
                                               Map<String, Object> output) {
        return new WorkerResultSubmitRequest(resultCorrelationRef, success, detail, errorCode, output);
    }

    private static String requireText(String value, String fieldName) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
