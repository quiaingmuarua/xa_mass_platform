package com.xa.mass.transport.websocket.session;

import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;

import java.util.List;

public final class WebSocketSessionEvidenceDriver {

    private final AdapterSessionEvidencePublisher sessionEvidencePublisher;

    public WebSocketSessionEvidenceDriver(AdapterSessionEvidencePublisher sessionEvidencePublisher) {
        this.sessionEvidencePublisher = java.util.Objects.requireNonNull(sessionEvidencePublisher,
                "sessionEvidencePublisher");
    }

    public long getLeaseMillis() {
        return sessionEvidencePublisher.leaseMillis();
    }

    public void connected(WebSocketSessionStore.SessionSnapshot session, String reason) {
        sessionEvidencePublisher.connected(
                session.workerId(),
                session.deliveryBucketId(),
                session.endpointAddress(),
                session.sessionHandle(),
                reason,
                session.sessionHandle()
        );
    }

    public void heartbeat(WebSocketSessionStore.SessionSnapshot session, String reason) {
        sessionEvidencePublisher.heartbeat(
                session.workerId(),
                session.deliveryBucketId(),
                session.endpointAddress(),
                session.sessionHandle(),
                reason,
                session.sessionHandle()
        );
    }

    public void disconnected(WebSocketSessionStore.SessionSnapshot session, String reason) {
        sessionEvidencePublisher.disconnected(
                session.workerId(),
                session.deliveryBucketId(),
                session.endpointAddress(),
                session.sessionHandle(),
                reason,
                session.sessionHandle()
        );
    }

    public void projectActiveSessions(List<WebSocketSessionStore.SessionSnapshot> sessions, String reason) {
        for (WebSocketSessionStore.SessionSnapshot session : sessions) {
            if (session != null) {
                sessionEvidencePublisher.claimEndpoint(
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
