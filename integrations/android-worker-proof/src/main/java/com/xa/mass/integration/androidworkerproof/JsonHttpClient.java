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

    private final URI baseUrl;
    private final Duration requestTimeout;
    private final HttpClient http;
    private final long requestStartDeadlineNanos;

    JsonHttpClient(URI baseUrl, Duration requestTimeout) {
        this(baseUrl, requestTimeout, Long.MAX_VALUE);
    }

    JsonHttpClient(URI baseUrl, AndroidWorkerProofOptions options) {
        this(
                baseUrl,
                options.requestTimeout(),
                options.phaseDeadlineNanos()
        );
    }

    private JsonHttpClient(
            URI baseUrl,
            Duration requestTimeout,
            long requestStartDeadlineNanos
    ) {
        this.baseUrl = requireHttpBase(baseUrl);
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
        if (System.nanoTime() >= requestStartDeadlineNanos) {
            throw new ProofFailure(
                    "proof.phase-time-budget",
                    "Android Worker proof phase exhausted its time budget"
            );
        }
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(endpoint(path))
                .timeout(requestTimeout);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(
                            method,
                            HttpRequest.BodyPublishers.ofString(
                                    Jsons.toJson(body),
                                    StandardCharsets.UTF_8
                            )
                    );
        }
        try {
            HttpResponse<String> response = http.send(
                    request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            Map<String, Object> decoded;
            try {
                decoded = Jsons.parseObject(response.body());
            } catch (IllegalArgumentException error) {
                throw new ProofFailure(
                        operation + ".json",
                        operation + " returned invalid JSON",
                        error
                );
            }
            return new Response(response.statusCode(), decoded);
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
