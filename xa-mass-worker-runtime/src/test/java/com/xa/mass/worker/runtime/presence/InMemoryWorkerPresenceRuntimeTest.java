package com.xa.mass.worker.runtime.presence;

import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryWorkerPresenceRuntimeTest {

    @Test
    void workerReachabilityIsOnlineWhileAnySessionIsActive() {
        InMemoryWorkerPresenceRuntime runtime = new InMemoryWorkerPresenceRuntime(Long.MAX_VALUE);

        assertEquals(WorkerReachabilityState.UNKNOWN, runtime.getWorkerReachability("worker-1"));

        WorkerPresenceChange firstConnect = runtime.sessionConnected(
                "worker-1", "websocket", "route-a", "session-a", 1_000L, "connected"
        );
        runtime.sessionConnected("worker-1", "socket", "route-b", "session-b", 1_001L, "connected");

        assertEquals(WorkerReachabilityState.UNKNOWN, firstConnect.previousState());
        assertEquals(WorkerReachabilityState.ONLINE, firstConnect.currentState());
        assertEquals(true, firstConnect.observationAccepted());
        assertEquals(WorkerReachabilityState.ONLINE, runtime.getWorkerReachability("worker-1"));
        assertEquals(2, runtime.activeSessionCount("worker-1"));

        runtime.sessionDisconnected("worker-1", "websocket", "route-a", "session-a", 1_002L, "disconnected");

        assertEquals(WorkerReachabilityState.ONLINE, runtime.getWorkerReachability("worker-1"));
        assertEquals(1, runtime.activeSessionCount("worker-1"));

        WorkerPresenceChange lastDisconnect = runtime.sessionDisconnected(
                "worker-1", "socket", "route-b", "session-b", 1_003L, "disconnected"
        );

        assertEquals(WorkerReachabilityState.ONLINE, lastDisconnect.previousState());
        assertEquals(WorkerReachabilityState.OFFLINE, lastDisconnect.currentState());
        assertEquals(true, lastDisconnect.observationAccepted());
        assertEquals(WorkerReachabilityState.OFFLINE, runtime.getWorkerReachability("worker-1"));
    }

    @Test
    void staleDisconnectOnlyClosesTheMatchingPresenceSessionKey() {
        InMemoryWorkerPresenceRuntime runtime = new InMemoryWorkerPresenceRuntime(Long.MAX_VALUE);

        runtime.sessionConnected("worker-1", "websocket", "route", "old-session", 1_000L, "connected");
        runtime.sessionConnected("worker-1", "websocket", "route", "new-session", 1_001L, "connected");

        WorkerPresenceChange staleDisconnect = runtime.sessionDisconnected(
                "worker-1", "websocket", "route", "old-session", 1_002L, "stale-disconnect"
        );

        assertEquals(WorkerReachabilityState.ONLINE, staleDisconnect.previousState());
        assertEquals(WorkerReachabilityState.ONLINE, staleDisconnect.currentState());
        assertEquals(false, staleDisconnect.changed());
        assertEquals(true, staleDisconnect.observationAccepted());
        assertEquals(WorkerReachabilityState.ONLINE, runtime.getWorkerReachability("worker-1"));
        assertEquals(1, runtime.activeSessionCount("worker-1"));
    }

    @Test
    void heartbeatOnlyRefreshesExistingSessionPresence() {
        InMemoryWorkerPresenceRuntime runtime = new InMemoryWorkerPresenceRuntime(Long.MAX_VALUE);

        WorkerPresenceChange heartbeat = runtime.sessionHeartbeat(
                "worker-1", "polling", "route", "session", 1_000L, "heartbeat"
        );

        assertEquals(WorkerReachabilityState.UNKNOWN, heartbeat.previousState());
        assertEquals(WorkerReachabilityState.UNKNOWN, heartbeat.currentState());
        assertEquals(false, heartbeat.changed());
        assertEquals(false, heartbeat.observationAccepted());
        assertEquals(WorkerReachabilityState.UNKNOWN, runtime.getWorkerReachability("worker-1"));
        assertEquals(0, runtime.activeSessionCount("worker-1"));

        runtime.sessionConnected("worker-1", "polling", "route", "session", 1_001L, "connected");

        WorkerPresenceChange secondHeartbeat = runtime.sessionHeartbeat(
                "worker-1", "polling", "route", "session", 1_100L, "heartbeat"
        );

        assertEquals(false, secondHeartbeat.changed());
        assertEquals(true, secondHeartbeat.observationAccepted());
        assertEquals(1, runtime.activeSessionCount("worker-1"));
    }

    @Test
    void staleHeartbeatDoesNotResurrectDisconnectedOrReplacedSessionPresence() {
        InMemoryWorkerPresenceRuntime runtime = new InMemoryWorkerPresenceRuntime(Long.MAX_VALUE);

        runtime.sessionConnected("worker-1", "websocket", "route", "old-session", 1_000L, "connected");
        runtime.sessionDisconnected("worker-1", "websocket", "route", "old-session", 1_001L, "disconnected");

        WorkerPresenceChange disconnectedHeartbeat = runtime.sessionHeartbeat(
                "worker-1", "websocket", "route", "old-session", 1_002L, "stale-heartbeat"
        );

        assertEquals(WorkerReachabilityState.OFFLINE, disconnectedHeartbeat.previousState());
        assertEquals(WorkerReachabilityState.OFFLINE, disconnectedHeartbeat.currentState());
        assertEquals(false, disconnectedHeartbeat.changed());
        assertEquals(false, disconnectedHeartbeat.observationAccepted());
        assertEquals(0, runtime.activeSessionCount("worker-1"));

        runtime.sessionConnected("worker-1", "websocket", "route", "new-session", 1_003L, "reconnected");
        WorkerPresenceChange oldHeartbeatAfterReplacement = runtime.sessionHeartbeat(
                "worker-1", "websocket", "route", "old-session", 1_004L, "stale-heartbeat"
        );

        assertEquals(WorkerReachabilityState.ONLINE, oldHeartbeatAfterReplacement.previousState());
        assertEquals(WorkerReachabilityState.ONLINE, oldHeartbeatAfterReplacement.currentState());
        assertEquals(false, oldHeartbeatAfterReplacement.changed());
        assertEquals(false, oldHeartbeatAfterReplacement.observationAccepted());
        assertEquals(1, runtime.activeSessionCount("worker-1"));
    }

    @Test
    void expiredSessionBecomesOfflineOnReadAndNewOnlineWakesDispatcher() {
        AtomicLong now = new AtomicLong(1_000L);
        AtomicInteger wakeups = new AtomicInteger();
        InMemoryWorkerPresenceRuntime runtime = new InMemoryWorkerPresenceRuntime(500L, now::get);
        runtime.setDispatchWakeupCallback(wakeups::incrementAndGet);

        runtime.sessionConnected("worker-1", "websocket", "route", "session", now.get(), "connected");
        assertEquals(1, wakeups.get());

        now.set(1_600L);
        assertEquals(WorkerReachabilityState.OFFLINE, runtime.getWorkerReachability("worker-1"));

        runtime.sessionConnected("worker-1", "websocket", "route", "session-2", now.get(), "reconnected");
        assertEquals(2, wakeups.get());
    }
}
