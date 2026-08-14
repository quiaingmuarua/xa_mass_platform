package com.xa.mass.integration.workercapability.runtimeapi;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class ScenarioRpcApiClient {

    private final RuntimeApiHttpClient http;

    public ScenarioRpcApiClient(RuntimeApiHttpClient http) {
        this.http = http;
    }

    public void uploadInput(String fileName, String content) {
        RuntimeApiHttpClient.ApiResponse response = http.uploadText(
                "/api/v1/scenario-rpc/input-files/"
                        + RuntimeApiHttpClient.identifier(fileName),
                content
        );
        RuntimeApiHttpClient.requireStatus(
                response,
                200,
                "scenarioRpc.uploadInput"
        );
    }

    public CreateResult create(String scenarioType) {
        RuntimeApiHttpClient.ApiResponse response = http.send(
                "POST",
                "/api/v1/scenario-rpc/scenarios",
                Map.of("scenarioType", scenarioType)
        );
        RuntimeApiHttpClient.requireStatus(
                response,
                201,
                "scenarioRpc.create"
        );
        Map<String, Object> body = response.body();
        return new CreateResult(
                requiredString(body, "scenarioId"),
                requiredString(body, "scenarioType"),
                requiredString(body, "status")
        );
    }

    public RunResult run(
            String scenarioId,
            String inputFile,
            long loadIntervalMillis,
            int maximumLoadRounds
    ) {
        RuntimeApiHttpClient.ApiResponse response = http.send(
                "POST",
                "/api/v1/scenario-rpc/scenarios/"
                        + RuntimeApiHttpClient.identifier(scenarioId)
                        + ":run",
                Map.of(
                        "inputFile", RuntimeApiHttpClient.identifier(
                                inputFile
                        ),
                        "loadIntervalMillis", loadIntervalMillis,
                        "maximumLoadRounds", maximumLoadRounds
                )
        );
        RuntimeApiHttpClient.requireStatus(
                response,
                200,
                "scenarioRpc.run"
        );
        Map<String, Object> body = response.body();
        return new RunResult(
                requiredString(body, "scenarioId"),
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
                        "/api/v1/scenario-rpc/output-files/"
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
                    "Scenario RPC response requires " + name
            );
        }
        return text;
    }

    private static int requiredInt(
            Map<String, Object> body,
            String name
    ) {
        Object value = body.get(name);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException(
                    "Scenario RPC response requires " + name
            );
        }
        return number.intValue();
    }

    private static long requiredLong(
            Map<String, Object> body,
            String name
    ) {
        Object value = body.get(name);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException(
                    "Scenario RPC response requires " + name
            );
        }
        return number.longValue();
    }

    public record CreateResult(
            String scenarioId,
            String scenarioType,
            String status
    ) {
    }

    public record RunResult(
            String scenarioId,
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
