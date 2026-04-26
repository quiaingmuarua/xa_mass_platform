package com.xa.mass.transport.model;

import java.util.Objects;

/**
 * Transport-neutral outbound message addressed to one concrete worker.
 *
 * <p>Concrete adapters may encode or route this message differently, but the
 * embedded runtime should not need adapter-local delivery DTOs just to place a
 * raw payload onto an adapter-owned outbound path.
 */
public final class WorkerTransportMessage {

    private final String workerId;
    private final String rawJson;
    private final String traceId;

    public WorkerTransportMessage(String workerId, String rawJson, String traceId) {
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.rawJson = Objects.requireNonNull(rawJson, "rawJson");
        this.traceId = traceId;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getRawJson() {
        return rawJson;
    }

    public String getTraceId() {
        return traceId;
    }
}
