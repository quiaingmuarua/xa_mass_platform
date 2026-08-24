package com.xa.mass.integration.workercapability.runtimeapi;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

public final class RuntimeApiHttpClient {

    private final URI baseUrl;
    private final Duration requestTimeout;
    private final HttpClient http;

    public RuntimeApiHttpClient(
            URI baseUrl,
            Duration requestTimeout
    ) {
        this.baseUrl = requireHttpBaseUrl(baseUrl);
        this.requestTimeout = requirePositive(
                requestTimeout,
                "requestTimeout"
        );
        http = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    ApiResponse send(
            String method,
            String path,
            Object body
    ) {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(
                        Jsons.toJson(body),
                        StandardCharsets.UTF_8
                );
        return sendJsonResponse(method, path, publisher, body == null
                ? null
                : "application/json");
    }

    BinaryApiResponse sendBinary(
            String method,
            String path,
            Object body
    ) {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(
                        Jsons.toJson(body),
                        StandardCharsets.UTF_8
                );
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(endpoint(path))
                .timeout(requestTimeout)
                .method(method, publisher);
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        try {
            HttpResponse<byte[]> response = http.send(
                    request.build(),
                    HttpResponse.BodyHandlers.ofByteArray()
            );
            return new BinaryApiResponse(
                    response.statusCode(),
                    response.body()
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Runtime API request interrupted: " + method + " " + path,
                    error
            );
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException(
                    "Runtime API request failed: " + method + " " + path,
                    error
            );
        }
    }

    private ApiResponse sendJsonResponse(
            String method,
            String path,
            HttpRequest.BodyPublisher publisher,
            String contentType
    ) {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(endpoint(path))
                .timeout(requestTimeout)
                .method(method, publisher);
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        try {
            HttpResponse<String> response = http.send(
                    request.build(),
                    HttpResponse.BodyHandlers.ofString(
                            StandardCharsets.UTF_8
                    )
            );
            Map<String, Object> responseBody;
            try {
                responseBody = response.body() == null
                        || response.body().isBlank()
                        ? Map.of()
                        : Jsons.parseObject(response.body());
            } catch (IllegalArgumentException error) {
                throw new IllegalStateException(
                        "Runtime API returned invalid JSON: "
                                + method
                                + " "
                                + path
                                + " HTTP "
                                + response.statusCode(),
                        error
                );
            }
            return new ApiResponse(
                    response.statusCode(),
                    responseBody
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Runtime API request interrupted: " + method + " " + path,
                    error
            );
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException(
                    "Runtime API request failed: " + method + " " + path,
                    error
            );
        }
    }

    static void requireStatus(
            ApiResponse response,
            int expected,
            String operation
    ) {
        if (response.statusCode != expected) {
            throw new IllegalStateException(
                    operation
                            + " returned HTTP "
                            + response.statusCode
            );
        }
    }

    public static String identifier(String value) {
        if (value == null
                || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(
                    "Integration identifiers must match "
                            + "[A-Za-z0-9._-]+"
            );
        }
        return value;
    }

    private URI endpoint(String path) {
        String base = baseUrl.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    private static URI requireHttpBaseUrl(URI value) {
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

    private static Duration requirePositive(
            Duration value,
            String name
    ) {
        if (value == null
                || value.isZero()
                || value.isNegative()) {
            throw new IllegalArgumentException(
                    name + " must be positive"
            );
        }
        return value;
    }

    record ApiResponse(
            int statusCode,
            Map<String, Object> body
    ) {
    }

    record BinaryApiResponse(
            int statusCode,
            byte[] body
    ) {

        BinaryApiResponse {
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}
