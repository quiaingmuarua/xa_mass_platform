package com.xa.mass.transport.websocket.session;

import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.runtime.delivery.DeliveryCommandConsumerRegistry;
import com.xa.mass.transport.runtime.lease.TransportEndpointLeasePublisher;
import com.xa.mass.transport.runtime.lease.WorkerPresenceSessionPublisher;

import java.util.List;

public final class WebSocketSessionEvidenceDriver {

    private final TransportEndpointLeasePublisher endpointLeasePublisher;
    private final WorkerPresenceSessionPublisher workerPresencePublisher;

    public WebSocketSessionEvidenceDriver(String adapterId) {
        this.endpointLeasePublisher = new TransportEndpointLeasePublisher(adapterId);
        this.workerPresencePublisher = new WorkerPresenceSessionPublisher(adapterId);
    }

    public void setEndpointLeaseStore(TransportEndpointLeaseStore endpointLeaseStore) {
        endpointLeasePublisher.setEndpointLeaseStore(endpointLeaseStore);
    }

    public void setDeliveryCommandConsumerRegistry(DeliveryCommandConsumerRegistry registry) {
        endpointLeasePublisher.setDeliveryCommandConsumerRegistry(registry);
    }

    public void setWorkerPresenceIngress(WorkerPresenceIngress workerPresenceIngress) {
        workerPresencePublisher.setWorkerPresenceIngress(workerPresenceIngress);
    }

    public long getLeaseMillis() {
        return endpointLeasePublisher.getLeaseMillis();
    }

    public void connected(WebSocketSessionStore.SessionSnapshot session, String reason) {
        workerPresencePublisher.sessionConnected(
                session.workerId(),
                session.endpointAddress(),
                session.sessionHandle(),
                reason,
                session.sessionHandle()
        );
        endpointLeasePublisher.claim(
                session.workerId(),
                session.deliveryBucketId(),
                session.endpointAddress(),
                session.sessionHandle(),
                reason
        );
    }

    public void heartbeat(WebSocketSessionStore.SessionSnapshot session, String reason) {
        workerPresencePublisher.sessionHeartbeat(
                session.workerId(),
                session.endpointAddress(),
                session.sessionHandle(),
                reason,
                session.sessionHandle()
        );
        endpointLeasePublisher.refresh(
                session.workerId(),
                session.deliveryBucketId(),
                session.endpointAddress(),
                session.sessionHandle(),
                reason
        );
    }

    public void disconnected(WebSocketSessionStore.SessionSnapshot session, String reason) {
        workerPresencePublisher.sessionDisconnected(
                session.workerId(),
                session.endpointAddress(),
                session.sessionHandle(),
                reason,
                session.sessionHandle()
        );
        endpointLeasePublisher.release(
                session.workerId(),
                session.deliveryBucketId(),
                session.endpointAddress(),
                session.sessionHandle(),
                reason
        );
    }

    public void projectActiveSessions(List<WebSocketSessionStore.SessionSnapshot> sessions, String reason) {
        for (WebSocketSessionStore.SessionSnapshot session : sessions) {
            if (session != null) {
                endpointLeasePublisher.claim(
                        session.workerId(),
                        session.deliveryBucketId(),
                        session.endpointAddress(),
                        session.sessionHandle(),
                        reason
                );
            }
        }
    }
}
