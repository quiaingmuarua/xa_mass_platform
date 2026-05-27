package com.xa.mass.runtime.worker;

/**
 * Read-only worker transport reachability view consumed by engine matching.
 */
public enum WorkerReachabilityState {
    ONLINE,
    STALE,
    OFFLINE,
    UNKNOWN
}
