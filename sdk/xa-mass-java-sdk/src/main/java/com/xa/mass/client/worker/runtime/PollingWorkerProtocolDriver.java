package com.xa.mass.client.worker.runtime;

import com.xa.mass.client.worker.WorkerClient;
import com.xa.mass.client.worker.WorkerAction;
import com.xa.mass.client.worker.WorkerActionReply;
import com.xa.mass.client.worker.WorkerPollRequest;
import com.xa.mass.client.worker.WorkerPollResult;
import com.xa.mass.client.worker.handler.WorkerActionResult;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class PollingWorkerProtocolDriver {
    private final WorkerClient workerClient;
    private final String workerId;
    private final String sessionToken;
    private final int maxMessages;
    private final long pollTimeoutMs;

    PollingWorkerProtocolDriver(WorkerClient workerClient,
                                String workerId,
                                int maxMessages,
                                long pollTimeoutMs) {
        this.workerClient = Objects.requireNonNull(workerClient, "workerClient is required");
        this.workerId = requireText(workerId, "workerId");
        this.sessionToken = UUID.randomUUID().toString();
        this.maxMessages = maxMessages;
        this.pollTimeoutMs = pollTimeoutMs;
    }

    String sessionToken() {
        return sessionToken;
    }

    void open() {
        workerClient.online(workerId, sessionToken, "polling-session-start");
    }

    void heartbeat() {
        workerClient.heartbeat(workerId, sessionToken, "polling-session-heartbeat");
    }

    List<WorkerAction> poll() {
        WorkerPollResult result = workerClient.poll(workerId, WorkerPollRequest.builder()
                .maxMessages(maxMessages)
                .timeoutMs(pollTimeoutMs)
                .build());
        return result.items() == null ? List.of() : result.items();
    }

    void submitActionReply(String replyRef, WorkerActionResult result) {
        workerClient.submitActionReply(workerId, new WorkerActionReply(
                replyRef,
                result.success(),
                result.code(),
                result.body()
        ));
    }

    void close() {
        workerClient.offline(workerId, sessionToken, "polling-session-close");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }
}
