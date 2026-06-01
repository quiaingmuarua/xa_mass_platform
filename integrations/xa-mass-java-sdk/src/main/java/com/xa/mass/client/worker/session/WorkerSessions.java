package com.xa.mass.client.worker.session;

import com.xa.mass.client.worker.WorkerClient;

import java.util.Objects;

public final class WorkerSessions {
    private final WorkerClient workerClient;

    public WorkerSessions(WorkerClient workerClient) {
        this.workerClient = Objects.requireNonNull(workerClient, "workerClient is required");
    }

    public PollingWorkerSession.Builder polling() {
        return PollingWorkerSession.builder(workerClient);
    }

    public WebSocketWorkerSession.Builder webSocket() {
        return WebSocketWorkerSession.builder(workerClient);
    }
}
