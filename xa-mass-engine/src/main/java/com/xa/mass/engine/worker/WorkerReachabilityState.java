package com.xa.mass.engine.worker;

/**
 * Read-only worker transport reachability view consumed by engine matching.
 */
public enum WorkerReachabilityState {
    ONLINE,
    STALE,
    OFFLINE,
    UNKNOWN
}
