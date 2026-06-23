package com.xa.mass.client.worker.handler;

public record WorkerActionResult(
        boolean success,
        String code,
        String body
) {
    public WorkerActionResult {
        code = normalize(code);
        body = normalize(body);
    }

    public static WorkerActionResult success(String body) {
        return new WorkerActionResult(true, null, body);
    }

    public static WorkerActionResult failure(String code, String body) {
        return new WorkerActionResult(false, code, body);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
