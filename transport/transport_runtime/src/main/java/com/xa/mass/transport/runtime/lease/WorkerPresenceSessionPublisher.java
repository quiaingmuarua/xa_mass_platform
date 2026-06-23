package com.xa.mass.transport.runtime.lease;

import com.xa.mass.transport.channel.NoopWorkerPresenceIngress;
import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.channel.WorkerSessionPresenceEvent;

import java.util.Locale;
import java.util.Objects;

/**
 * Publishes adapter session observations into worker-runtime presence ingress.
 */
public final class WorkerPresenceSessionPublisher {

    private final String adapterId;
    private final String adapterMailboxKey;
    private volatile WorkerPresenceIngress workerPresenceIngress = NoopWorkerPresenceIngress.INSTANCE;

    public WorkerPresenceSessionPublisher(String adapterId, String adapterMailboxKey) {
        this.adapterId = requireText(adapterId, "adapterId").toLowerCase(Locale.ROOT);
        this.adapterMailboxKey = requireText(adapterMailboxKey, "adapterMailboxKey");
    }

    public void setWorkerPresenceIngress(WorkerPresenceIngress workerPresenceIngress) {
        this.workerPresenceIngress = workerPresenceIngress != null
                ? workerPresenceIngress
                : NoopWorkerPresenceIngress.INSTANCE;
    }

    public void sessionConnected(String workerId,
                                 String sessionToken,
                                 String reason,
                                 String traceId) {
        workerPresenceIngress.sessionConnected(WorkerSessionPresenceEvent.connected(
                workerId,
                adapterId,
                adapterMailboxKey,
                null,
                sessionToken,
                reason,
                traceId
        ));
    }

    public void sessionHeartbeat(String workerId,
                                 String sessionToken,
                                 String reason,
                                 String traceId) {
        workerPresenceIngress.sessionHeartbeat(WorkerSessionPresenceEvent.heartbeat(
                workerId,
                adapterId,
                adapterMailboxKey,
                null,
                sessionToken,
                reason,
                traceId
        ));
    }

    public void sessionDisconnected(String workerId,
                                    String sessionToken,
                                    String reason,
                                    String traceId) {
        workerPresenceIngress.sessionDisconnected(WorkerSessionPresenceEvent.disconnected(
                workerId,
                adapterId,
                adapterMailboxKey,
                null,
                sessionToken,
                reason,
                traceId
        ));
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
