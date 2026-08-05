package com.xa.mass.scenarioworkers;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class HttpScenarioWorkerResourceClient
        implements ScenarioWorkerResourceClient {

    private static final int WORKER_RESOURCE_FAILED = 14003;
    private static final Set<String> RESULT_FIELDS = Set.of(
            "status",
            "reason"
    );
    private static final Set<String> INDEX_RESPONSE_FIELDS = Set.of(
            "results"
    );

    private final URI runtimeApiBaseUrl;
    private final HttpClient httpClient;

    HttpScenarioWorkerResourceClient(URI runtimeApiBaseUrl) {
        this(runtimeApiBaseUrl, HttpClient.newHttpClient());
    }

    HttpScenarioWorkerResourceClient(
            URI runtimeApiBaseUrl,
            HttpClient httpClient
    ) {
        this.runtimeApiBaseUrl = requireRuntimeApiBaseUrl(
                runtimeApiBaseUrl
        );
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public void registerWorker(
            String workerGroupId,
            String workerId,
            String endpointManagerId,
            Map<String, Object> workerProperties,
            Duration timeout
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("endpointManagerId", endpointManagerId);
        body.put("workerProperties", workerProperties);
        ScenarioWorkerResourceResult result = requireCommandResult(
                send(
                        "PUT",
                        workerPath(workerGroupId, workerId),
                        body,
                        timeout,
                        "resourceApi.registerWorker"
                ),
                "resourceApi.registerWorker"
        );
        requireAccepted(
                result,
                "resourceApi.registerWorker",
                workerGroupId,
                workerId
        );
    }

    @Override
    public void updateWorkerProperties(
            String workerGroupId,
            String workerId,
            Map<String, Object> workerProperties,
            Duration timeout
    ) {
        ScenarioWorkerResourceResult result = requireCommandResult(
                send(
                        "PUT",
                        workerPath(workerGroupId, workerId)
                                + "/worker-properties",
                        Map.of("properties", workerProperties),
                        timeout,
                        "resourceApi.updateWorkerProperties"
                ),
                "resourceApi.updateWorkerProperties"
        );
        requireAccepted(
                result,
                "resourceApi.updateWorkerProperties",
                workerGroupId,
                workerId
        );
    }

    @Override
    public Map<String, ScenarioWorkerResourceResult>
    updateIndexedProperties(
            String workerGroupId,
            String workerId,
            Map<String, Object> updates,
            Duration timeout
    ) {
        Map<String, Object> response = send(
                "PATCH",
                workerPath(workerGroupId, workerId)
                        + "/indexed-properties",
                Map.of("updates", updates),
                timeout,
                "resourceApi.updateIndexedProperties"
        );
        requireExactFields(
                response,
                INDEX_RESPONSE_FIELDS,
                "indexed properties response"
        );
        Map<String, Object> rawResults = requireObject(
                response.get("results"),
                "results"
        );
        if (!rawResults.keySet().equals(updates.keySet())) {
            throw protocolFailure(
                    "resourceApi.updateIndexedProperties",
                    "Indexed properties response fields do not match request"
            );
        }
        Map<String, ScenarioWorkerResourceResult> results =
                new LinkedHashMap<>();
        rawResults.forEach((field, rawResult) -> results.put(
                field,
                requireCommandResult(
                        requireObject(rawResult, "result " + field),
                        "resourceApi.updateIndexedProperties"
                )
        ));
        return Collections.unmodifiableMap(results);
    }

    private Map<String, Object> send(
            String method,
            String path,
            Map<String, Object> body,
            Duration timeout,
            String operation
    ) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        HttpRequest request = HttpRequest.newBuilder(resolve(path))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .method(
                        method,
                        HttpRequest.BodyPublishers.ofString(
                                Jsons.toJson(body),
                                StandardCharsets.UTF_8
                        )
                )
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(
                            StandardCharsets.UTF_8
                    )
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ScenarioWorkerAssemblyException(
                    WORKER_RESOURCE_FAILED,
                    operation,
                    "Worker Resource request was interrupted",
                    error
            );
        } catch (IOException | RuntimeException error) {
            throw new ScenarioWorkerAssemblyException(
                    WORKER_RESOURCE_FAILED,
                    operation,
                    "Worker Resource request failed",
                    error
            );
        }
        if (response.statusCode() != 200) {
            throw new ScenarioWorkerAssemblyException(
                    WORKER_RESOURCE_FAILED,
                    operation,
                    "Worker Resource API returned HTTP "
                            + response.statusCode()
            );
        }
        try {
            return Jsons.parseObject(response.body());
        } catch (IllegalArgumentException error) {
            throw new ScenarioWorkerAssemblyException(
                    WORKER_RESOURCE_FAILED,
                    operation,
                    "Worker Resource API returned invalid JSON",
                    error
            );
        }
    }

    private ScenarioWorkerResourceResult requireCommandResult(
            Map<String, Object> response,
            String operation
    ) {
        requireExactFields(response, RESULT_FIELDS, "command response");
        Object rawStatus = response.get("status");
        Object rawReason = response.get("reason");
        if (!(rawStatus instanceof String)
                || ((String) rawStatus).isBlank()
                || (rawReason != null && !(rawReason instanceof String))) {
            throw protocolFailure(
                    operation,
                    "Worker Resource command response is invalid"
            );
        }
        return new ScenarioWorkerResourceResult(
                (String) rawStatus,
                (String) rawReason
        );
    }

    private static void requireAccepted(
            ScenarioWorkerResourceResult result,
            String operation,
            String workerGroupId,
            String workerId
    ) {
        if (result.accepted()) {
            return;
        }
        throw new ScenarioWorkerAssemblyException(
                WORKER_RESOURCE_FAILED,
                operation,
                "Worker "
                        + workerGroupId
                        + "/"
                        + workerId
                        + " returned "
                        + result.status()
        );
    }

    private String workerPath(String workerGroupId, String workerId) {
        return "/api/v1/worker-groups/"
                + encodePathSegment(workerGroupId)
                + "/workers/"
                + encodePathSegment(workerId);
    }

    private URI resolve(String path) {
        String base = runtimeApiBaseUrl.toASCIIString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    private static String encodePathSegment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Resource path coordinate must be non-blank"
            );
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static URI requireRuntimeApiBaseUrl(URI value) {
        Objects.requireNonNull(value, "runtimeApiBaseUrl");
        String scheme = value.getScheme();
        if (!value.isAbsolute()
                || value.getHost() == null
                || value.getQuery() != null
                || value.getFragment() != null
                || (!"http".equalsIgnoreCase(scheme)
                && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException(
                    "runtimeApiBaseUrl must be an absolute HTTP(S) URI"
            );
        }
        return value;
    }

    private static ScenarioWorkerAssemblyException protocolFailure(
            String operation,
            String message
    ) {
        return new ScenarioWorkerAssemblyException(
                WORKER_RESOURCE_FAILED,
                operation,
                message
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireObject(
            Object value,
            String owner
    ) {
        if (!(value instanceof Map<?, ?>)) {
            throw protocolFailure(
                    "resourceApi.decodeResponse",
                    owner + " must be an object"
            );
        }
        return new LinkedHashMap<>((Map<String, Object>) value);
    }

    private static void requireExactFields(
            Map<String, Object> value,
            Set<String> expected,
            String owner
    ) {
        if (!value.keySet().equals(expected)) {
            throw protocolFailure(
                    "resourceApi.decodeResponse",
                    owner + " has invalid fields"
            );
        }
    }
}
