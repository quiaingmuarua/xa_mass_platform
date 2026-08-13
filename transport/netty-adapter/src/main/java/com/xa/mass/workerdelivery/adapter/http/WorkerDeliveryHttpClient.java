package com.xa.mass.workerdelivery.adapter.http;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Thread-safe physical HTTP client shared by Adapter owner processes. */
public final class WorkerDeliveryHttpClient {

    private static final String POST_OPERATION = "workerDeliveryHttp.post";

    private final HttpClient http;
    private final URI baseUrl;
    private final Duration requestTimeout;

    public WorkerDeliveryHttpClient(
            URI baseUrl,
            Duration requestTimeout
    ) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(requireTimeout(requestTimeout))
                        .version(HttpClient.Version.HTTP_1_1)
                        .build(),
                baseUrl,
                requestTimeout
        );
    }

    WorkerDeliveryHttpClient(
            HttpClient http,
            URI baseUrl,
            Duration requestTimeout
    ) {
        this.http = Objects.requireNonNull(http, "http");
        this.baseUrl = requireBaseUrl(baseUrl);
        this.requestTimeout = requireTimeout(requestTimeout);
    }

    public HttpCallResult postJson(
            String relativePath,
            String jsonBody
    ) {
        Objects.requireNonNull(jsonBody, "jsonBody");
        HttpRequest request = request(relativePath)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        jsonBody,
                        StandardCharsets.UTF_8
                ))
                .build();
        try {
            HttpResponse<String> response = http.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(
                            StandardCharsets.UTF_8
                    )
            );
            return new HttpCallResult(
                    response.statusCode(),
                    response.body()
            );
        } catch (IOException error) {
            throw unavailable("Worker Delivery HTTP request failed", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw unavailable(
                    "Worker Delivery HTTP request was interrupted",
                    error
            );
        }
    }

    public CompletionStage<HttpCallResult> postEmptyAsync(
            String relativePath
    ) {
        HttpRequest request = request(relativePath)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return http.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        ).handle((response, failure) -> {
            if (failure != null) {
                throw unavailable(
                        "Worker Delivery HTTP request failed",
                        failure
                );
            }
            return new HttpCallResult(
                    response.statusCode(),
                    response.body()
            );
        });
    }

    public static String encodePathSegment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "path segment must be non-blank"
            );
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private HttpRequest.Builder request(String relativePath) {
        String requiredPath = requireRelativePath(relativePath);
        URI uri = URI.create(trimTrailingSlash(baseUrl.toString())
                + requiredPath);
        return HttpRequest.newBuilder(uri).timeout(requestTimeout);
    }

    private static WorkerDeliveryAdapterException unavailable(
            String message,
            Throwable cause
    ) {
        return new WorkerDeliveryAdapterException(
                WorkerDeliveryAdapterErrorCode.REMOTE_API_UNAVAILABLE,
                POST_OPERATION,
                message,
                cause
        );
    }

    private static String requireRelativePath(String value) {
        if (value == null || value.isBlank() || !value.startsWith("/")) {
            throw new IllegalArgumentException(
                    "relativePath must start with '/'"
            );
        }
        URI parsed = URI.create(value);
        if (parsed.isAbsolute()
                || parsed.getRawAuthority() != null
                || parsed.getRawQuery() != null
                || parsed.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "relativePath must not contain authority, query, or fragment"
            );
        }
        return value;
    }

    private static String trimTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private static URI requireBaseUrl(URI value) {
        Objects.requireNonNull(value, "baseUrl");
        String scheme = value.getScheme();
        if (!value.isAbsolute()
                || value.getHost() == null
                || value.getRawQuery() != null
                || value.getRawFragment() != null
                || !("http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException(
                    "baseUrl must be an absolute HTTP(S) URI without "
                            + "query or fragment"
            );
        }
        return value;
    }

    private static Duration requireTimeout(Duration value) {
        Objects.requireNonNull(value, "requestTimeout");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "requestTimeout must be positive"
            );
        }
        return value;
    }

    public record HttpCallResult(
            int statusCode,
            String body
    ) {

        public HttpCallResult {
            body = body == null ? "" : body;
        }
    }
}
