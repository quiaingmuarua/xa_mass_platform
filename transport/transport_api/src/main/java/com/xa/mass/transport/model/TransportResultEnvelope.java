package com.xa.mass.transport.model;

import java.util.Locale;
import java.util.Objects;

/**
 * Transport-neutral envelope for task execution results reported by workers.
 *
 * <p>The report remains the protocol payload. Adapter metadata is captured here
 * so runtime code can reason about ingress without expanding wire formats.</p>
 */
public final class TransportResultEnvelope {

    private final String adapterId;
    private final String workerId;
    private final String endpointId;
    private final TaskResultReport report;

    public TransportResultEnvelope(String adapterId,
                                   String workerId,
                                   String endpointId,
                                   TaskResultReport report) {
        this.adapterId = normalize(adapterId);
        this.workerId = normalizeBlank(workerId);
        this.endpointId = normalizeBlank(endpointId);
        this.report = Objects.requireNonNull(report, "report");
    }

    public static TransportResultEnvelope fromReport(String adapterId,
                                                     String workerId,
                                                     String endpointId,
                                                     TaskResultReport report) {
        return new TransportResultEnvelope(adapterId, workerId, endpointId, report);
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
