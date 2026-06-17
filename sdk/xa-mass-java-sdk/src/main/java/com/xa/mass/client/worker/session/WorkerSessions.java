package com.xa.mass.client.worker.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.client.UnstableApi;
import com.xa.mass.client.worker.WorkerClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;

public final class WorkerSessions {
    private final WorkerClient workerClient;
    private final Duration connectTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @UnstableApi("Prefer MassPlatform.workerSessions(); direct WorkerSessions construction is advanced/internal wiring.")
    public WorkerSessions(WorkerClient workerClient) {
        this(workerClient, Duration.ofSeconds(10), null, new ObjectMapper().findAndRegisterModules());
    }

    @UnstableApi("Prefer MassPlatform.workerSessions(); direct WorkerSessions construction is advanced/internal wiring.")
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

    public PollingWorkerSession.Builder polling(WorkerSessionSpec spec) {
        WorkerSessionSpec resolved = Objects.requireNonNull(spec, "spec is required");
        return polling()
                .workerId(resolved.workerId())
                .workerGroupId(resolved.workerGroupId())
                .attributes(resolved.attributes())
                .eventHandlers(resolved.eventHandlers())
                .listener(resolved.listener());
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

    public WebSocketWorkerSession.Builder webSocket(WorkerSessionSpec spec) {
        WorkerSessionSpec resolved = Objects.requireNonNull(spec, "spec is required");
        return webSocket()
                .workerId(resolved.workerId())
                .workerGroupId(resolved.workerGroupId())
                .attributes(resolved.attributes())
                .eventHandlers(resolved.eventHandlers())
                .listener(resolved.listener());
    }
}
