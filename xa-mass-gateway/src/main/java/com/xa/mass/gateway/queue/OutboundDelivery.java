package com.xa.mass.gateway.queue;

import com.xa.mass.transport.WorkerEndpointRoles;

/**
 * Minimal adapter-local output delivery record.
 */
public final class OutboundDelivery {

    private final String workerId;
    private final String connRole;
    private final String rawJson;
    private final String traceId;

    public OutboundDelivery(String workerId, String connRole, String rawJson, String traceId) {
        this.workerId = workerId;
        this.connRole = normalizeConnRole(connRole);
        this.rawJson = rawJson;
        this.traceId = traceId;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getConnRole() {
        return connRole;
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
                + ", connRole='" + connRole + '\''
                + ", traceId='" + traceId + '\''
                + ", rawJson=" + (rawJson != null ? rawJson.substring(0, Math.min(100, rawJson.length())) + "..." : null)
                + '}';
    }

    private static String normalizeConnRole(String connRole) {
        if (connRole == null || connRole.isBlank()) {
            return WorkerEndpointRoles.TASK_DISPATCH;
        }
        return connRole.trim();
    }
}
