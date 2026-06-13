package com.xa.mass.transport.channel;

import java.util.Objects;

/**
 * Transport-neutral session presence evidence.
 *
 * <p>This event describes a transport session observation. It is not worker
 * state-report truth, worker capability truth, or route-owner delivery truth.</p>
 */
public record WorkerSessionPresenceEvent(String workerId,
                                         String adapterId,
                                         String routeKey,
                                         String sessionToken,
                                         WorkerPresenceEventType eventType,
                                         long observedAtMillis,
                                         String reason,
                                         String traceId) {

    public WorkerSessionPresenceEvent {
        workerId = requireText(workerId, "workerId");
        adapterId = requireText(adapterId, "adapterId");
        sessionToken = requireText(sessionToken, "sessionToken");
        routeKey = normalizeNullable(routeKey);
        eventType = Objects.requireNonNull(eventType, "eventType");
        observedAtMillis = observedAtMillis > 0L ? observedAtMillis : System.currentTimeMillis();
        reason = normalizeNullable(reason);
        traceId = normalizeNullable(traceId);
    }

    public static WorkerSessionPresenceEvent connected(String workerId,
                                                       String adapterId,
                                                       String routeKey,
                                                       String sessionToken,
                                                       String reason,
                                                       String traceId) {
        return new WorkerSessionPresenceEvent(
                workerId,
                adapterId,
                routeKey,
                sessionToken,
                WorkerPresenceEventType.CONNECTED,
                System.currentTimeMillis(),
                reason,
                traceId
        );
    }

    public static WorkerSessionPresenceEvent heartbeat(String workerId,
                                                       String adapterId,
                                                       String routeKey,
                                                       String sessionToken,
                                                       String reason,
                                                       String traceId) {
        return new WorkerSessionPresenceEvent(
                workerId,
                adapterId,
                routeKey,
                sessionToken,
                WorkerPresenceEventType.HEARTBEAT,
                System.currentTimeMillis(),
                reason,
                traceId
        );
    }

    public static WorkerSessionPresenceEvent disconnected(String workerId,
                                                          String adapterId,
                                                          String routeKey,
                                                          String sessionToken,
                                                          String reason,
                                                          String traceId) {
        return new WorkerSessionPresenceEvent(
                workerId,
                adapterId,
                routeKey,
                sessionToken,
                WorkerPresenceEventType.DISCONNECTED,
                System.currentTimeMillis(),
                reason,
                traceId
        );
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
