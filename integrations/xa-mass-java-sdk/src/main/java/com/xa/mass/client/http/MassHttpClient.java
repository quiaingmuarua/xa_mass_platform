package com.xa.mass.client.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.client.http.exception.MassApiException;
import com.xa.mass.client.http.exception.MassClientException;
import com.xa.mass.client.http.exception.MassHttpException;
import com.xa.mass.client.http.exception.MassProtocolException;
import com.xa.mass.client.http.exception.MassTimeoutException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;

public final class MassHttpClient {
    public static final String MASS_API_KEY_HEADER = "X-Mass-Api-Key";

    private final URI baseUri;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AuthHeader authHeader;
    private final Duration requestTimeout;

    public MassHttpClient(URI baseUri,
                          HttpClient httpClient,
                          ObjectMapper objectMapper,
                          AuthHeader authHeader,
                          Duration requestTimeout) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri is required");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.authHeader = Objects.requireNonNull(authHeader, "authHeader is required");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout is required");
    }

    public <T> T get(String path, Class<T> dataType) {
        return send("GET", path, null, dataType);
    }

    public <T> T post(String path, Object body, Class<T> dataType) {
        return send("POST", path, body, dataType);
    }

    public <T> T send(String method, String path, Object body, Class<T> dataType) {
        Objects.requireNonNull(method, "method is required");
        Objects.requireNonNull(path, "path is required");
        Objects.requireNonNull(dataType, "dataType is required");
        URI uri = resolve(path);
        String normalizedMethod = method.toUpperCase();
        HttpRequest request = buildRequest(normalizedMethod, uri, body);
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new MassTimeoutException(normalizedMethod, path, requestTimeout, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MassClientException("Interrupted while calling " + normalizedMethod + " " + path, e);
        } catch (IOException e) {
            throw new MassClientException("I/O failure while calling " + normalizedMethod + " " + path, e);
        }
        return decodeEnvelope(normalizedMethod, path, response, dataType);
    }

    public URI resolve(String path) {
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        return baseUri.resolve(normalized);
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    private HttpRequest buildRequest(String method, URI uri, Object body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header(authHeader.name(), authHeader.value())
                .header("Accept", "application/json");
        if ("GET".equals(method)) {
            return builder.GET().build();
        }
        String json = encodeBody(body);
        return builder
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    private String encodeBody(Object body) {
        if (body == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new MassProtocolException("Failed to encode request body", e);
        }
    }

    private <T> T decodeEnvelope(String method, String path, HttpResponse<String> response, Class<T> dataType) {
        String body = response.body() == null ? "" : response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new MassHttpException(
                    method,
                    path,
                    response.statusCode(),
                    authHeader.redact(body)
            );
        }
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new MassProtocolException("Failed to decode ApiResponse from " + method + " " + path, e);
        }
        JsonNode codeNode = envelope.get("code");
        if (codeNode == null || !codeNode.canConvertToInt()) {
            throw new MassProtocolException("ApiResponse code is missing or invalid for " + method + " " + path);
        }
        int code = codeNode.asInt();
        String message = envelope.hasNonNull("msg") ? envelope.get("msg").asText() : "";
        if (code != 0) {
            throw new MassApiException(method, path, response.statusCode(), code, authHeader.redact(message));
        }
        JsonNode data = envelope.get("data");
        if (dataType == Void.class) {
            return null;
        }
        try {
            return objectMapper.treeToValue(data, dataType);
        } catch (JsonProcessingException e) {
            throw new MassProtocolException("Failed to decode ApiResponse data from " + method + " " + path, e);
        }
    }

    public record AuthHeader(String name, String value) {
        public AuthHeader {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("auth header name is required");
            }
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("auth token is required");
            }
        }

        public static AuthHeader of(String name, String value) {
            return new AuthHeader(name, value);
        }

        public String redact(String text) {
            if (text == null || text.isEmpty()) {
                return text;
            }
            return text.replace(value, "[REDACTED]");
        }
    }
}
