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

    public void connected(WebSocketSessionRecord record, String reason) {
        workerPresencePublisher.sessionConnected(
                record.workerId(),
                record.endpointAddress(),
                record.sessionHandle(),
                reason,
                record.sessionHandle()
        );
        endpointLeasePublisher.claim(
                record.workerId(),
                record.deliveryBucketId(),
                record.endpointAddress(),
                record.sessionHandle(),
                reason
        );
    }

    public void heartbeat(WebSocketSessionRecord record, String reason) {
        workerPresencePublisher.sessionHeartbeat(
                record.workerId(),
                record.endpointAddress(),
                record.sessionHandle(),
                reason,
                record.sessionHandle()
        );
        endpointLeasePublisher.refresh(
                record.workerId(),
                record.deliveryBucketId(),
                record.endpointAddress(),
                record.sessionHandle(),
                reason
        );
    }

    public void disconnected(WebSocketSessionRecord record, String reason) {
        workerPresencePublisher.sessionDisconnected(
                record.workerId(),
                record.endpointAddress(),
                record.sessionHandle(),
                reason,
                record.sessionHandle()
        );
        endpointLeasePublisher.release(
                record.workerId(),
                record.deliveryBucketId(),
                record.endpointAddress(),
                record.sessionHandle(),
                reason
        );
    }

    public void projectActiveSessions(List<WebSocketSessionRecord> records, String reason) {
        for (WebSocketSessionRecord record : records) {
            if (record != null && record.isActive()) {
                endpointLeasePublisher.claim(
                        record.workerId(),
                        record.deliveryBucketId(),
                        record.endpointAddress(),
                        record.sessionHandle(),
                        reason
                );
            }
        }
    }
}
