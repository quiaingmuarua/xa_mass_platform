package com.xa.mass.client.worker.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.client.worker.WorkerAction;
import com.xa.mass.client.worker.WorkerActionReply;
import com.xa.mass.client.worker.WorkerChannelFrame;
import com.xa.mass.client.worker.handler.WorkerActionResult;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

final class WebSocketWorkerProtocolDriver {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final String workerId;
    private final String workerGroupId;
    private final URI endpoint;
    private final Duration connectTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final WebSocketConnector webSocketConnector;

    WebSocketWorkerProtocolDriver(String workerId,
                                  String workerGroupId,
                                  URI endpoint,
                                  Duration connectTimeout,
                                  HttpClient httpClient,
                                  ObjectMapper objectMapper,
                                  WebSocketConnector webSocketConnector) {
        this.workerId = requireText(workerId, "workerId");
        this.workerGroupId = requireText(workerGroupId, "workerGroupId");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint is required");
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout is required");
        this.httpClient = httpClient;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.webSocketConnector = webSocketConnector == null
                ? new DefaultWebSocketConnector(httpClient, connectTimeout)
                : webSocketConnector;
    }

    Duration connectTimeout() {
        return connectTimeout;
    }

    HttpClient httpClient() {
        return httpClient;
    }

    ObjectMapper objectMapper() {
        return objectMapper;
    }

    WebSocket connect(WebSocket.Listener listener)
            throws ExecutionException, InterruptedException, TimeoutException {
        return webSocketConnector.connect(connectUri(), listener)
                .get(connectTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    WorkerAction decodeDispatchFrame(String frame) throws JsonProcessingException {
        WorkerChannelFrame channelFrame = objectMapper.readValue(frame, WorkerChannelFrame.class);
        if (!WorkerChannelFrame.ACTION.equals(channelFrame.kind())) {
            return null;
        }
        JsonNode root = objectMapper.readTree(channelFrame.body());
        return new WorkerAction(
                text(root, "actionId"),
                text(root, "replyRef"),
                text(root, "eventCode"),
                body(root.get("body")),
                objectMap(root.get("sharedConfig"))
        );
    }

    String encodeResultFrame(String replyRef, WorkerActionResult result) throws JsonProcessingException {
        WorkerActionReply reply = new WorkerActionReply(
                replyRef,
                result.success(),
                result.code(),
                result.body()
        );
        WorkerChannelFrame frame = new WorkerChannelFrame(
                UUID.randomUUID().toString(),
                WorkerChannelFrame.ACTION_REPLY,
                objectMapper.writeValueAsString(reply)
        );
        return objectMapper.writeValueAsString(frame);
    }

    private URI connectUri() {
        String raw = endpoint.toString();
        String separator = endpoint.getRawQuery() == null ? "?" : "&";
        return URI.create(raw
                + separator
                + "workerId=" + encodeQuery(workerId)
                + "&workerGroupId=" + encodeQuery(workerGroupId));
    }

    private Map<String, Object> objectMap(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, MAP_TYPE);
    }

    private String body(JsonNode node) throws JsonProcessingException {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.isTextual() ? node.asText() : objectMapper.writeValueAsString(node);
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    private static final class DefaultWebSocketConnector implements WebSocketConnector {
        private final HttpClient httpClient;
        private final Duration connectTimeout;

        private DefaultWebSocketConnector(HttpClient httpClient, Duration connectTimeout) {
            this.httpClient = httpClient == null ? HttpClient.newHttpClient() : httpClient;
            this.connectTimeout = connectTimeout;
        }

        @Override
        public CompletableFuture<WebSocket> connect(URI endpoint, WebSocket.Listener listener) {
            return httpClient.newWebSocketBuilder()
                    .connectTimeout(connectTimeout)
                    .buildAsync(endpoint, listener);
        }
    }
}
