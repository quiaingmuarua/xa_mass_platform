package com.xa.mass.sdk.worker;

/**
 * Embedded SDK worker result submission.
 */
public record WorkerResultSubmission(String resultCorrelationRef,
                                     boolean success,
                                     String resultCode,
                                     String result) {

    public WorkerResultSubmission {
        resultCorrelationRef = requireText(resultCorrelationRef, "resultCorrelationRef");
        resultCode = normalize(resultCode);
        result = normalize(result);
    }

    public static WorkerResultSubmission of(String resultCorrelationRef,
                                            boolean success,
                                            String resultCode,
                                            String result) {
        return new WorkerResultSubmission(resultCorrelationRef, success, resultCode, result);
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
