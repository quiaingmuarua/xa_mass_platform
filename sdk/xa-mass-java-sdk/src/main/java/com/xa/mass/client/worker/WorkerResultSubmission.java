package com.xa.mass.client.worker;

public record WorkerResultSubmission(
        String resultCorrelationRef,
        boolean success,
        String resultCode,
        String result
) {
    public WorkerResultSubmission {
        resultCorrelationRef = requireText(resultCorrelationRef, "resultCorrelationRef");
        resultCode = normalize(resultCode);
        result = normalize(result);
    }

    public static WorkerResultSubmission success(String resultCorrelationRef, String result) {
        return new WorkerResultSubmission(resultCorrelationRef, true, null, result);
    }

    public static WorkerResultSubmission failure(String resultCorrelationRef, String resultCode, String result) {
        return new WorkerResultSubmission(resultCorrelationRef, false, resultCode, result);
    }

    private static String requireText(String value, String fieldName) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " is required");
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
