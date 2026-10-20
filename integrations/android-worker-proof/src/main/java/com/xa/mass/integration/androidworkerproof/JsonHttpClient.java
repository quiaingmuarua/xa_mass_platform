package com.xa.mass.integration.androidworkerproof;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class JsonHttpClient {
    private final Map<String, String> taskCallerHeaders;

    private final URI baseUrl;
    private final Duration requestTimeout;
    private final HttpClient http;
    private final long requestStartDeadlineNanos;

    JsonHttpClient(URI baseUrl, Duration requestTimeout, Map<String, String> taskCallerHeaders) {
        this(baseUrl, requestTimeout, Long.MAX_VALUE, taskCallerHeaders);
    }

    JsonHttpClient(URI baseUrl, AndroidWorkerProofOptions options, Map<String, String> taskCallerHeaders) {
        this(
                baseUrl,
                options.requestTimeout(),
                options.phaseDeadlineNanos(), taskCallerHeaders
        );
    }

    private JsonHttpClient(
            URI baseUrl,
            Duration requestTimeout,
            long requestStartDeadlineNanos, Map<String, String> taskCallerHeaders
    ) {
        this.baseUrl = requireHttpBase(baseUrl);
        this.taskCallerHeaders = Map.copyOf(taskCallerHeaders);
        if (requestTimeout == null
                || requestTimeout.isZero()
                || requestTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "requestTimeout must be positive"
            );
        }
        this.requestTimeout = requestTimeout;
        this.requestStartDeadlineNanos = requestStartDeadlineNanos;
        http = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    Response send(String method, String path, Object body, String operation) {
        HttpResponse<String> response = sendText(method, path, body == null ? null : Jsons.toJson(body), "application/json", operation);
        try {
            return new Response(response.statusCode(), Jsons.parseObject(response.body()));
        } catch (IllegalArgumentException error) {
            throw new ProofFailure(operation + ".json", operation + " returned invalid JSON", error);
        }
    }

    HttpResponse<String> sendText(String method, String path, String body, String contentType, String operation) {
        if (System.nanoTime() >= requestStartDeadlineNanos) {
            throw new ProofFailure(
                    "proof.phase-time-budget",
                    "Android Worker proof phase exhausted its time budget"
            );
        }
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(endpoint(path))
                .timeout(requestTimeout);
        if (path.startsWith("/api/v1/tasks") || path.startsWith("/api/v1/projects") || path.startsWith("/api/v1/runtime-view/tasks")
                || path.startsWith("/api/v1/app-checks") || path.startsWith("/api/v1/messages")
                || path.startsWith("/api/v1/users")) taskCallerHeaders.forEach(request::header);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", contentType)
                    .method(
                            method,
                            HttpRequest.BodyPublishers.ofString(
                                    body,
                                    StandardCharsets.UTF_8
                            )
                    );
        }
        try {
            return http.send(
                    request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ProofFailure(
                    operation + ".interrupted",
                    operation + " was interrupted",
                    error
            );
        } catch (IOException error) {
            throw new TransientObservationFailure(
                    operation + " request failed",
                    error
            );
        }
    }

    private URI endpoint(String path) {
        String base = baseUrl.toString();
        return URI.create((base.endsWith("/")
                ? base.substring(0, base.length() - 1)
                : base) + path);
    }

    private static URI requireHttpBase(URI value) {
        if (value == null
                || value.getHost() == null
                || (!"http".equalsIgnoreCase(value.getScheme())
                && !"https".equalsIgnoreCase(value.getScheme()))) {
            throw new IllegalArgumentException(
                    "baseUrl must be an absolute HTTP(S) URI"
            );
        }
        return value;
    }

    record Response(int statusCode, Map<String, Object> body) {
        Response {
            body = Collections.unmodifiableMap(new LinkedHashMap<>(body));
        }
    }
}
