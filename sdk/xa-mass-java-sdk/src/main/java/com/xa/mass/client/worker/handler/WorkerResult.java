package com.xa.mass.client.worker.handler;

public record WorkerResult(
        boolean success,
        String resultCode,
        String result
) {
    public WorkerResult {
        resultCode = normalize(resultCode);
        result = normalize(result);
    }

    public static WorkerResult success(String result) {
        return new WorkerResult(true, null, result);
    }

    public static WorkerResult failure(String resultCode, String result) {
        return new WorkerResult(false, resultCode, result);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
