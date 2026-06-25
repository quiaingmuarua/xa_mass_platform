package com.xa.mass.transport.channel;

/**
 * Transport-neutral ingress for worker session presence.
 *
 * <p>Implementations may project session evidence into worker-runtime
 * reachability and selected-worker mailbox evidence. They must not treat
 * endpoint lease store results as worker lifecycle truth or dispatch
 * eligibility truth.</p>
 */
public interface WorkerPresenceIngress {

    void sessionConnected(WorkerSessionPresenceEvent event);

    void sessionHeartbeat(WorkerSessionPresenceEvent event);

    void sessionDisconnected(WorkerSessionPresenceEvent event);
}
