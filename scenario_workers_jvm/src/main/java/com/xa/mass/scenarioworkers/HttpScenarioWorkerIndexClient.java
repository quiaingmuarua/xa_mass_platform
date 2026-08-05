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

final class HttpScenarioWorkerIndexClient
        implements ScenarioWorkerIndexClient {

    private static final int WORKER_INDEX_FAILED = 14010;
    private static final Set<String> RESULT_FIELDS = Set.of(
            "status",
            "reason"
    );
    private static final Set<String> RESPONSE_FIELDS = Set.of("results");

    private final URI runtimeApiBaseUrl;
    private final HttpClient httpClient;

    HttpScenarioWorkerIndexClient(URI runtimeApiBaseUrl) {
        this(runtimeApiBaseUrl, HttpClient.newHttpClient());
    }

    HttpScenarioWorkerIndexClient(
            URI runtimeApiBaseUrl,
            HttpClient httpClient
    ) {
        this.runtimeApiBaseUrl = requireRuntimeApiBaseUrl(
                runtimeApiBaseUrl
        );
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public Map<String, ScenarioWorkerIndexResult>
    updateIndexedProperties(
            String workerGroupId,
            String workerId,
            Map<String, Object> updates,
            Duration timeout
    ) {
        Map<String, Object> response = send(
                workerPath(workerGroupId, workerId)
                        + "/indexed-properties",
                Map.of("updates", updates),
                timeout
        );
        requireExactFields(response, RESPONSE_FIELDS, "index response");
        Map<String, Object> rawResults = requireObject(
                response.get("results"),
                "results"
        );
        if (!rawResults.keySet().equals(updates.keySet())) {
            throw failure(
                    "Indexed properties response fields do not match request",
                    null
            );
        }
        Map<String, ScenarioWorkerIndexResult> results =
                new LinkedHashMap<>();
        rawResults.forEach((field, rawResult) -> results.put(
                field,
                requireResult(requireObject(rawResult, "result " + field))
        ));
        return Collections.unmodifiableMap(results);
    }

    private Map<String, Object> send(
            String path,
            Map<String, Object> body,
            Duration timeout
    ) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        HttpRequest request = HttpRequest.newBuilder(resolve(path))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .method(
                        "PATCH",
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
            throw failure("Worker Index request was interrupted", error);
        } catch (IOException | RuntimeException error) {
            throw failure("Worker Index request failed", error);
        }
        if (response.statusCode() != 200) {
            throw failure(
                    "Worker Index API returned HTTP "
                            + response.statusCode(),
                    null
            );
        }
        try {
            return Jsons.parseObject(response.body());
        } catch (IllegalArgumentException error) {
            throw failure("Worker Index API returned invalid JSON", error);
        }
    }

    private static ScenarioWorkerIndexResult requireResult(
            Map<String, Object> response
    ) {
        requireExactFields(response, RESULT_FIELDS, "index field result");
        Object status = response.get("status");
        Object reason = response.get("reason");
        if (!(status instanceof String)
                || ((String) status).isBlank()
                || (reason != null && !(reason instanceof String))) {
            throw failure("Worker Index response is invalid", null);
        }
        return new ScenarioWorkerIndexResult(
                (String) status,
                (String) reason
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
                    "Index path coordinate must be non-blank"
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

    private static ScenarioWorkerAssemblyException failure(
            String message,
            Throwable cause
    ) {
        return new ScenarioWorkerAssemblyException(
                WORKER_INDEX_FAILED,
                "workerPropertyIndex.update",
                message,
                cause
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireObject(
            Object value,
            String owner
    ) {
        if (!(value instanceof Map<?, ?>)) {
            throw failure(owner + " must be an object", null);
        }
        return new LinkedHashMap<>((Map<String, Object>) value);
    }

    private static void requireExactFields(
            Map<String, Object> value,
            Set<String> expected,
            String owner
    ) {
        if (!value.keySet().equals(expected)) {
            throw failure(owner + " has invalid fields", null);
        }
    }
}
