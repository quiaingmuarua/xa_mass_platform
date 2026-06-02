package com.xa.mass.client.worker.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.client.worker.WorkerClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;

public final class WorkerSessions {
    private final WorkerClient workerClient;
    private final Duration connectTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public WorkerSessions(WorkerClient workerClient) {
        this(workerClient, Duration.ofSeconds(10), null, new ObjectMapper().findAndRegisterModules());
    }

    public WorkerSessions(WorkerClient workerClient,
                          Duration connectTimeout,
                          HttpClient httpClient,
                          ObjectMapper objectMapper) {
        this.workerClient = Objects.requireNonNull(workerClient, "workerClient is required");
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout is required");
        this.httpClient = httpClient;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    }

    public PollingWorkerSession.Builder polling() {
        return PollingWorkerSession.builder(workerClient);
    }

    public WebSocketWorkerSession.Builder webSocket() {
        WebSocketWorkerSession.Builder builder = WebSocketWorkerSession.builder(workerClient)
                .connectTimeout(connectTimeout)
                .objectMapper(objectMapper);
        if (httpClient != null) {
            builder.httpClient(httpClient);
        }
        return builder;
    }
}
