package com.xa.mass.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.client.http.MassHttpClient;
import com.xa.mass.client.task.TaskClient;
import com.xa.mass.client.worker.WorkerClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;

public final class MassPlatform {
    private final URI baseUri;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final MassHttpClient httpClient;
    private final TaskClient taskClient;
    private final WorkerClient workerClient;

    private MassPlatform(Builder builder) {
        this.baseUri = normalizeBaseUri(builder.baseUri);
        this.connectTimeout = builder.connectTimeout;
        this.requestTimeout = builder.requestTimeout;
        HttpClient client = builder.httpClient != null
                ? builder.httpClient
                : HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        ObjectMapper objectMapper = builder.objectMapper != null
                ? builder.objectMapper
                : new ObjectMapper().findAndRegisterModules();
        this.httpClient = new MassHttpClient(
                baseUri,
                client,
                objectMapper,
                MassHttpClient.AuthHeader.of(builder.authHeaderName, builder.authToken),
                requestTimeout
        );
        this.taskClient = new TaskClient(httpClient);
        this.workerClient = new WorkerClient(httpClient);
    }

    public static Builder builder() {
        return new Builder();
    }

    public URI baseUri() {
        return baseUri;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public MassHttpClient http() {
        return httpClient;
    }

    public TaskClient tasks() {
        return taskClient;
    }

    public WorkerClient workers() {
        return workerClient;
    }

    private static URI normalizeBaseUri(URI baseUri) {
        Objects.requireNonNull(baseUri, "baseUrl is required");
        if (!baseUri.isAbsolute()) {
            throw new IllegalArgumentException("baseUrl must be absolute: " + baseUri);
        }
        String value = baseUri.toString();
        return value.endsWith("/") ? baseUri : URI.create(value + "/");
    }

    public static final class Builder {
        private URI baseUri;
        private String authHeaderName = MassHttpClient.MASS_API_KEY_HEADER;
        private String authToken;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration requestTimeout = Duration.ofSeconds(30);
        private HttpClient httpClient;
        private ObjectMapper objectMapper;

        private Builder() {
        }

        public Builder baseUrl(String baseUrl) {
            Objects.requireNonNull(baseUrl, "baseUrl is required");
            return baseUri(URI.create(baseUrl));
        }

        public Builder baseUri(URI baseUri) {
            this.baseUri = Objects.requireNonNull(baseUri, "baseUri is required");
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.authHeaderName = MassHttpClient.MASS_API_KEY_HEADER;
            this.authToken = requireNonBlank(apiKey, "apiKey");
            return this;
        }

        public Builder bearerToken(String bearerToken) {
            this.authHeaderName = "Authorization";
            this.authToken = "Bearer " + requireNonBlank(bearerToken, "bearerToken");
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = requirePositive(connectTimeout, "connectTimeout");
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
            return this;
        }

        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient is required");
            return this;
        }

        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
            return this;
        }

        public MassPlatform build() {
            if (authToken == null || authToken.isBlank()) {
                throw new IllegalStateException("apiKey or bearerToken is required");
            }
            return new MassPlatform(this);
        }

        private static Duration requirePositive(Duration value, String fieldName) {
            Objects.requireNonNull(value, fieldName + " is required");
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(fieldName + " must be positive");
            }
            return value;
        }

        private static String requireNonBlank(String value, String fieldName) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(fieldName + " is required");
            }
            return value;
        }
    }
}
