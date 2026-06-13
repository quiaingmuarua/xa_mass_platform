package com.xa.mass.worker.runtime.presence;

import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;

/**
 * Result of applying one worker session-presence observation.
 */
public record WorkerPresenceChange(String workerId,
                                   WorkerReachabilityState previousState,
                                   WorkerReachabilityState currentState,
                                   String reason,
                                   boolean changed,
                                   boolean observationAccepted) {

    public boolean becameReachable() {
        return previousState != WorkerReachabilityState.ONLINE
                && currentState == WorkerReachabilityState.ONLINE;
    }

    public boolean becameUnreachable() {
        return previousState == WorkerReachabilityState.ONLINE
                && currentState != WorkerReachabilityState.ONLINE;
    }
}
