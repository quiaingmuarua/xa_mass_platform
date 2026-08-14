package com.xa.mass.integration.workercapability.runtimeapi;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class TaskBatchApiClient {

    private final RuntimeApiHttpClient http;

    public TaskBatchApiClient(RuntimeApiHttpClient http) {
        this.http = http;
    }

    public void uploadInput(String fileName, String content) {
        RuntimeApiHttpClient.ApiResponse response = http.uploadText(
                "/api/v1/task-batches/input-files/"
                        + RuntimeApiHttpClient.identifier(fileName),
                content
        );
        RuntimeApiHttpClient.requireStatus(
                response,
                200,
                "taskBatch.uploadInput"
        );
    }

    public RunResult run(
            String workerGroupId,
            String eventCode,
            String payloadKey,
            String inputFile,
            long maximumWaitMillis
    ) {
        RuntimeApiHttpClient.ApiResponse response = http.send(
                "POST",
                "/api/v1/task-batches/runs",
                Map.of(
                        "workerGroupId", workerGroupId,
                        "eventCode", eventCode,
                        "payloadKey", payloadKey,
                        "inputFile", RuntimeApiHttpClient.identifier(inputFile),
                        "maximumWaitMillis", maximumWaitMillis
                )
        );
        RuntimeApiHttpClient.requireStatus(response, 200, "taskBatch.run");
        Map<String, Object> body = response.body();
        return new RunResult(
                requiredString(body, "runId"),
                requiredString(body, "workerGroupId"),
                requiredString(body, "eventCode"),
                requiredString(body, "payloadKey"),
                requiredString(body, "status"),
                requiredString(body, "inputFile"),
                requiredInt(body, "inputCount"),
                requiredInt(body, "resultCount"),
                requiredInt(body, "remainingCount"),
                requiredInt(body, "loadRounds"),
                requiredLong(body, "durationMillis"),
                requiredString(body, "outputFile")
        );
    }

    public String downloadOutput(String fileName) {
        return new String(
                http.download(
                        "/api/v1/task-batches/output-files/"
                                + RuntimeApiHttpClient.identifier(fileName)
                ),
                StandardCharsets.UTF_8
        );
    }

    private static String requiredString(
            Map<String, Object> body,
            String name
    ) {
        Object value = body.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException(
                    "Task Batch response requires " + name
            );
        }
        return text;
    }

    private static int requiredInt(Map<String, Object> body, String name) {
        Object value = body.get(name);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException(
                    "Task Batch response requires " + name
            );
        }
        return number.intValue();
    }

    private static long requiredLong(Map<String, Object> body, String name) {
        Object value = body.get(name);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException(
                    "Task Batch response requires " + name
            );
        }
        return number.longValue();
    }

    public record RunResult(
            String runId,
            String workerGroupId,
            String eventCode,
            String payloadKey,
            String status,
            String inputFile,
            int inputCount,
            int resultCount,
            int remainingCount,
            int loadRounds,
            long durationMillis,
            String outputFile
    ) {
    }
}
