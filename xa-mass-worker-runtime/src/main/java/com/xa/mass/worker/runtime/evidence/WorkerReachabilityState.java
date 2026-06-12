package com.xa.mass.worker.runtime.evidence;

/**
 * Read-only worker-runtime reachability view consumed by engine matching.
 */
public enum WorkerReachabilityState {
    ONLINE,
    STALE,
    OFFLINE,
    UNKNOWN
}
