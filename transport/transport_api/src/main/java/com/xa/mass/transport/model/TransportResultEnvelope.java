package com.xa.mass.transport.model;

import java.util.Locale;
import java.util.Objects;

/**
 * Transport-neutral envelope for task execution results reported by workers.
 *
 * <p>The report remains the protocol payload. Adapter metadata is captured here
 * so runtime code can reason about ingress without expanding wire formats.
 * This envelope is internal transport/runtime metadata; it is not a second
 * worker result protocol.</p>
 *
 * <p>{@code leaseToken} is reserved for a future explicit security design. Do
 * not enforce it until generation, storage, expiry, retry compatibility, and
 * rejection semantics are defined.</p>
 */
public final class TransportResultEnvelope {

    private final String adapterId;
    private final String routeKey;
    private final String workerId;
    private final String endpointId;
    private final String attemptId;
    private final String leaseToken;
    private final String traceId;
    private final TaskResultReport report;

    public TransportResultEnvelope(String adapterId,
                                   String routeKey,
                                   String workerId,
                                   String endpointId,
                                   TaskResultReport report) {
        this(adapterId, routeKey, workerId, endpointId, null, null, null, report);
    }

    public TransportResultEnvelope(String adapterId,
                                   String routeKey,
                                   String workerId,
                                   String endpointId,
                                   String attemptId,
                                   TaskResultReport report) {
        this(adapterId, routeKey, workerId, endpointId, attemptId, null, null, report);
    }

    public TransportResultEnvelope(String adapterId,
                                   String routeKey,
                                   String workerId,
                                   String endpointId,
                                   String attemptId,
                                   String leaseToken,
                                   TaskResultReport report) {
        this(adapterId, routeKey, workerId, endpointId, attemptId, leaseToken, null, report);
    }

    public TransportResultEnvelope(String adapterId,
                                   String routeKey,
                                   String workerId,
                                   String endpointId,
                                   String attemptId,
                                   String leaseToken,
                                   String traceId,
                                   TaskResultReport report) {
        this.adapterId = normalize(adapterId);
        this.routeKey = normalizeBlank(routeKey);
        this.workerId = normalizeBlank(workerId);
        this.endpointId = normalizeBlank(endpointId);
        this.attemptId = normalizeBlank(attemptId);
        this.leaseToken = normalizeBlank(leaseToken);
        this.traceId = normalizeBlank(traceId);
        this.report = Objects.requireNonNull(report, "report");
    }

    public String getAdapterId() {
        return adapterId;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getRouteKey() {
        return routeKey;
    }

    public String getEndpointId() {
        return endpointId;
    }

    public String getAttemptId() {
        return attemptId;
    }

    public String getLeaseToken() {
        return leaseToken;
    }

    public String getTraceId() {
        return traceId;
    }

    public TaskResultReport getReport() {
        return report;
    }

    public String getTaskId() {
        return report.getTaskId();
    }

    public String getMessageId() {
        return report.getMessageId();
    }

    private static String normalize(String value) {
        String normalized = normalizeBlank(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private static String normalizeBlank(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
