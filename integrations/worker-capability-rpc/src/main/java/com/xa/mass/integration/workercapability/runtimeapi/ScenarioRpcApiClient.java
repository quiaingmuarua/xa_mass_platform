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

    public RunResult run(
            String scenarioId,
            String inputFile,
            int concurrency
    ) {
        RuntimeApiHttpClient.ApiResponse response = http.send(
                "POST",
                "/api/v1/scenario-rpc/runs",
                Map.of(
                        "scenarioId", RuntimeApiHttpClient.identifier(
                                scenarioId
                        ),
                        "inputFile", RuntimeApiHttpClient.identifier(
                                inputFile
                        ),
                        "concurrency", concurrency
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
                requiredString(body, "workerGroupId"),
                requiredString(body, "eventCode"),
                requiredString(body, "inputFile"),
                requiredString(body, "outputFile"),
                requiredInt(body, "inputCount"),
                requiredInt(body, "resultCount")
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

    public record RunResult(
            String scenarioId,
            String workerGroupId,
            String eventCode,
            String inputFile,
            String outputFile,
            int inputCount,
            int resultCount
    ) {
    }
}
