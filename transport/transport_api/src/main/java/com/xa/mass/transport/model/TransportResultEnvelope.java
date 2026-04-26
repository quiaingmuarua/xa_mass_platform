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
    private final String workerId;
    private final String endpointId;
    private final String attemptId;
    private final String leaseToken;
    private final TaskResultReport report;

    public TransportResultEnvelope(String adapterId,
                                   String workerId,
                                   String endpointId,
                                   TaskResultReport report) {
        this(adapterId, workerId, endpointId, null, null, report);
    }

    public TransportResultEnvelope(String adapterId,
                                   String workerId,
                                   String endpointId,
                                   String attemptId,
                                   String leaseToken,
                                   TaskResultReport report) {
        this.adapterId = normalize(adapterId);
        this.workerId = normalizeBlank(workerId);
        this.endpointId = normalizeBlank(endpointId);
        this.attemptId = normalizeBlank(attemptId);
        this.leaseToken = normalizeBlank(leaseToken);
        this.report = Objects.requireNonNull(report, "report");
    }

    public static TransportResultEnvelope fromReport(String adapterId,
                                                     String workerId,
                                                     String endpointId,
                                                     TaskResultReport report) {
        return new TransportResultEnvelope(adapterId, workerId, endpointId, report);
    }

    public static TransportResultEnvelope fromDispatchItem(String adapterId,
                                                           String endpointId,
                                                           TaskDispatchItem item,
                                                           TaskResultReport report) {
        return new TransportResultEnvelope(
                adapterId,
                item != null ? item.getWorkerId() : null,
                endpointId,
                item != null ? item.attemptId() : null,
                null,
                report
        );
    }

    public String getAdapterId() {
        return adapterId;
    }

    public String getWorkerId() {
        return workerId;
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
