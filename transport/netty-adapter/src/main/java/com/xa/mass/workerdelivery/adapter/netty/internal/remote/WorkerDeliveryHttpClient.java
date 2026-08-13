package com.xa.mass.workerdelivery.adapter.netty.internal.remote;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Physical HTTP transport shared by one Adapter's remote APIs. */
public final class WorkerDeliveryHttpClient {

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

    String postJson(
            String relativePath,
            String jsonBody,
            int expectedStatus
    ) {
        Objects.requireNonNull(jsonBody, "jsonBody");
        int requiredStatus = requireExpectedStatus(expectedStatus);
        HttpRequest request = request(relativePath)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        jsonBody,
                        StandardCharsets.UTF_8
                ))
                .build();
        try {
            return requireStatus(
                    http.send(
                            request,
                            HttpResponse.BodyHandlers.ofString(
                                    StandardCharsets.UTF_8
                            )
                    ),
                    requiredStatus
            );
        } catch (IOException error) {
            throw new RequestFailure(
                    "Worker Delivery HTTP request failed",
                    error
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new RequestFailure(
                    "Worker Delivery HTTP request was interrupted",
                    error
            );
        }
    }

    CompletionStage<Void> postEmptyAsync(
            String relativePath,
            int expectedStatus
    ) {
        int requiredStatus = requireExpectedStatus(expectedStatus);
        HttpRequest request = request(relativePath)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return http.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        ).handle((response, failure) -> {
            if (failure != null) {
                throw new RequestFailure(
                        "Worker Delivery HTTP request failed",
                        unwrap(failure)
                );
            }
            requireStatus(response, requiredStatus);
            return null;
        });
    }

    static String encodePathSegment(String value) {
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

    private static String requireStatus(
            HttpResponse<String> response,
            int expectedStatus
    ) {
        if (response.statusCode() != expectedStatus) {
            throw new UnexpectedStatus(response.statusCode());
        }
        return response.body() == null ? "" : response.body();
    }

    private static int requireExpectedStatus(int value) {
        if (value < 100 || value > 599) {
            throw new IllegalArgumentException(
                    "expectedStatus must be a valid HTTP status"
            );
        }
        return value;
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

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    static final class UnexpectedStatus extends RuntimeException {

        private final int statusCode;

        UnexpectedStatus(int statusCode) {
            super("Unexpected HTTP status " + statusCode);
            this.statusCode = statusCode;
        }

        int statusCode() {
            return statusCode;
        }
    }

    static final class RequestFailure extends RuntimeException {

        RequestFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
