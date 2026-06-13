package com.xa.mass.worker.runtime.presence;

import com.xa.mass.worker.runtime.evidence.WorkerReachabilityView;

/**
 * Worker-runtime owner for session presence and derived reachability evidence.
 */
public interface WorkerPresenceRuntime extends WorkerReachabilityView {

    WorkerPresenceChange sessionConnected(String workerId,
                                          String adapterId,
                                          String routeKey,
                                          String sessionToken,
                                          long observedAtMillis,
                                          String reason);

    WorkerPresenceChange sessionHeartbeat(String workerId,
                                          String adapterId,
                                          String routeKey,
                                          String sessionToken,
                                          long observedAtMillis,
                                          String reason);

    WorkerPresenceChange sessionDisconnected(String workerId,
                                             String adapterId,
                                             String routeKey,
                                             String sessionToken,
                                             long observedAtMillis,
                                             String reason);

    void setDispatchWakeupCallback(Runnable dispatchWakeupCallback);
}
