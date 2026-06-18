package com.xa.mass.client.worker.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.client.UnstableApi;
import com.xa.mass.client.worker.WorkerClient;
import com.xa.mass.client.worker.WorkerRuntimeDefinition;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;

public final class WorkerRuntimes {
    private final WorkerClient workerClient;
    private final Duration connectTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @UnstableApi("Prefer MassPlatform.workerRuntimes(); direct WorkerRuntimes construction is advanced/internal wiring.")
    public WorkerRuntimes(WorkerClient workerClient) {
        this(workerClient, Duration.ofSeconds(10), null, new ObjectMapper().findAndRegisterModules());
    }

    @UnstableApi("Prefer MassPlatform.workerRuntimes(); direct WorkerRuntimes construction is advanced/internal wiring.")
    public WorkerRuntimes(WorkerClient workerClient,
                          Duration connectTimeout,
                          HttpClient httpClient,
                          ObjectMapper objectMapper) {
        this.workerClient = Objects.requireNonNull(workerClient, "workerClient is required");
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout is required");
        this.httpClient = httpClient;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    }

    public PollingWorkerRuntime.Builder polling(WorkerRuntimeDefinition definition) {
        return PollingWorkerRuntime.builder(workerClient, definition);
    }

    public WebSocketWorkerRuntime.Builder webSocket(WorkerRuntimeDefinition definition) {
        WebSocketWorkerRuntime.Builder builder = WebSocketWorkerRuntime.builder(workerClient, definition)
                .connectTimeout(connectTimeout)
                .objectMapper(objectMapper);
        if (httpClient != null) {
            builder.httpClient(httpClient);
        }
        return builder;
    }

    public WorkerRuntimeReporter reporter(WorkerRuntimeDefinition definition) {
        return new WorkerRuntimeReporter(workerClient, definition);
    }
}
