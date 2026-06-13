package com.xa.mass.transport.channel;

/**
 * No-op presence ingress used by transport-only runtimes.
 */
public final class NoopWorkerPresenceIngress implements WorkerPresenceIngress {

    public static final NoopWorkerPresenceIngress INSTANCE = new NoopWorkerPresenceIngress();

    private NoopWorkerPresenceIngress() {
    }

    @Override
    public void sessionConnected(WorkerSessionPresenceEvent event) {
    }

    @Override
    public void sessionHeartbeat(WorkerSessionPresenceEvent event) {
    }

    @Override
    public void sessionDisconnected(WorkerSessionPresenceEvent event) {
    }
}
