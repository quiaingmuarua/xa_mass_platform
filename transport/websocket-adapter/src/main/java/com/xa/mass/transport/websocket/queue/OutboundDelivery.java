package com.xa.mass.transport.websocket.queue;

/**
 * Minimal adapter-local output delivery record.
 */
public final class OutboundDelivery {

    private final String workerId;
    private final String rawJson;
    private final String traceId;

    public OutboundDelivery(String workerId, String rawJson, String traceId) {
        this.workerId = workerId;
        this.rawJson = rawJson;
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

    @Override
    public String toString() {
        return "OutboundDelivery{"
                + "workerId='" + workerId + '\''
                + ", traceId='" + traceId + '\''
                + ", rawJson=" + (rawJson != null ? rawJson.substring(0, Math.min(100, rawJson.length())) + "..." : null)
                + '}';
    }
}
