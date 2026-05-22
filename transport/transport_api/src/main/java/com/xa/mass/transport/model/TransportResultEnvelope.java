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
    private final String attemptId;
    private final String leaseToken;
    private final String traceId;
    private final TaskResultReport report;

    public static TransportResultEnvelope addressed(String adapterId,
                                                    String routeKey,
                                                    TaskResultReport report) {
        return new TransportResultEnvelope(adapterId, routeKey, null, null, null, report);
    }

    public TransportResultEnvelope(String adapterId,
                                   String routeKey,
                                   String attemptId,
                                   String leaseToken,
                                   String traceId,
                                   TaskResultReport report) {
        this.adapterId = requireAdapterId(adapterId);
        this.routeKey = requireText(routeKey, "routeKey");
        this.attemptId = normalizeBlank(attemptId);
        this.leaseToken = normalizeBlank(leaseToken);
        this.traceId = normalizeBlank(traceId);
        this.report = Objects.requireNonNull(report, "report");
    }

    public String getAdapterId() {
        return adapterId;
    }

    public String getRouteKey() {
        return routeKey;
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

    private static String requireAdapterId(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        return normalized;
    }

    private static String requireText(String value, String fieldName) {
        String normalized = normalizeBlank(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeBlank(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
