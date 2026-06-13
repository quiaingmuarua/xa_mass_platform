package com.xa.mass.transport.channel;

/**
 * Transport-neutral ingress for worker session presence.
 *
 * <p>Implementations project session evidence into the worker-runtime
 * reachability owner. They must not treat route-owner store results as worker
 * lifecycle truth.</p>
 */
public interface WorkerPresenceIngress {

    void sessionConnected(WorkerSessionPresenceEvent event);

    void sessionHeartbeat(WorkerSessionPresenceEvent event);

    void sessionDisconnected(WorkerSessionPresenceEvent event);
}
