package com.xa.mass.transport.runtime.lease;

import com.xa.mass.transport.channel.NoopWorkerPresenceIngress;
import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.channel.WorkerSessionPresenceEvent;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;

import java.util.Locale;

/**
 * Narrow adapter-facing capability for publishing worker session evidence.
 *
 * <p>Concrete adapters observe protocol sessions. They may publish those
 * observations through this capability, but they do not own the endpoint lease
 * store or worker-runtime presence ingress.</p>
 */
public final class AdapterSessionEvidencePublisher {

    private final String adapterId;
    private final String adapterMailboxKey;
    private final TransportEndpointLeasePublisher endpointLeasePublisher;
    private final WorkerPresenceIngress workerPresenceIngress;

    public AdapterSessionEvidencePublisher(String adapterId,
                                           String adapterMailboxKey,
                                           TransportEndpointLeaseStore endpointLeaseStore,
                                           WorkerPresenceIngress workerPresenceIngress) {
        String normalizedAdapterId = requireText(adapterId, "adapterId").toLowerCase(Locale.ROOT);
        this.adapterId = normalizedAdapterId;
        this.adapterMailboxKey = requireText(adapterMailboxKey, "adapterMailboxKey");
        this.endpointLeasePublisher = new TransportEndpointLeasePublisher(normalizedAdapterId);
        this.endpointLeasePublisher.setEndpointLeaseStore(endpointLeaseStore);
        this.workerPresenceIngress = workerPresenceIngress != null
                ? workerPresenceIngress
                : NoopWorkerPresenceIngress.INSTANCE;
    }

    public static AdapterSessionEvidencePublisher noop(String adapterId, String adapterMailboxKey) {
        return new AdapterSessionEvidencePublisher(
                adapterId,
                adapterMailboxKey,
                new InMemoryTransportEndpointLeaseStore(),
                NoopWorkerPresenceIngress.INSTANCE
        );
    }

    public long leaseMillis() {
        return endpointLeasePublisher.getLeaseMillis();
    }

    public void connected(String workerId,
                          String deliveryBucketId,
                          String sessionToken,
                          String reason,
                          String traceId) {
        workerPresenceIngress.sessionConnected(connectedEvent(workerId, sessionToken, reason, traceId));
        endpointLeasePublisher.claim(workerId, deliveryBucketId, sessionToken, reason);
    }

    public boolean heartbeat(String workerId,
                             String deliveryBucketId,
                             String sessionToken,
                             String reason,
                             String traceId) {
        workerPresenceIngress.sessionHeartbeat(heartbeatEvent(workerId, sessionToken, reason, traceId));
        return endpointLeasePublisher.refresh(workerId, deliveryBucketId, sessionToken, reason);
    }

    public boolean disconnected(String workerId,
                                String deliveryBucketId,
                                String sessionToken,
                                String reason,
                                String traceId) {
        workerPresenceIngress.sessionDisconnected(disconnectedEvent(workerId, sessionToken, reason, traceId));
        return endpointLeasePublisher.release(workerId, deliveryBucketId, sessionToken, reason);
    }

    public void claimEndpoint(String workerId,
                              String deliveryBucketId,
                              String sessionToken,
                              String reason) {
        endpointLeasePublisher.claim(workerId, deliveryBucketId, sessionToken, reason);
    }

    private WorkerSessionPresenceEvent connectedEvent(String workerId,
                                                      String sessionToken,
                                                      String reason,
                                                      String traceId) {
        return WorkerSessionPresenceEvent.connected(
                workerId, adapterId, adapterMailboxKey, null, sessionToken, reason, traceId);
    }

    private WorkerSessionPresenceEvent heartbeatEvent(String workerId,
                                                      String sessionToken,
                                                      String reason,
                                                      String traceId) {
        return WorkerSessionPresenceEvent.heartbeat(
                workerId, adapterId, adapterMailboxKey, null, sessionToken, reason, traceId);
    }

    private WorkerSessionPresenceEvent disconnectedEvent(String workerId,
                                                         String sessionToken,
                                                         String reason,
                                                         String traceId) {
        return WorkerSessionPresenceEvent.disconnected(
                workerId, adapterId, adapterMailboxKey, null, sessionToken, reason, traceId);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
