package com.xa.mass.worker.runtime.presence;

import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * In-memory worker presence projection.
 *
 * <p>Presence identity is {@code workerId + adapterId + sessionToken}. Route key
 * is retained as diagnostic metadata only and never participates in currentness
 * decisions.</p>
 */
public final class InMemoryWorkerPresenceRuntime implements WorkerPresenceRuntime {

    public static final long DEFAULT_SESSION_TIMEOUT_MILLIS = 300_000L;

    private final long sessionTimeoutMillis;
    private final LongSupplier clock;
    private final Map<PresenceSessionKey, PresenceSessionRecord> activeSessions = new HashMap<>();
    private final Set<String> seenWorkers = new HashSet<>();
    private Runnable dispatchWakeupCallback = () -> {
    };

    public InMemoryWorkerPresenceRuntime() {
        this(DEFAULT_SESSION_TIMEOUT_MILLIS, System::currentTimeMillis);
    }

    public InMemoryWorkerPresenceRuntime(long sessionTimeoutMillis) {
        this(sessionTimeoutMillis, System::currentTimeMillis);
    }

    public InMemoryWorkerPresenceRuntime(long sessionTimeoutMillis, LongSupplier clock) {
        this.sessionTimeoutMillis = sessionTimeoutMillis > 0L ? sessionTimeoutMillis : Long.MAX_VALUE;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized WorkerPresenceChange sessionConnected(String workerId,
                                                             String adapterId,
                                                             String routeKey,
                                                             String sessionToken,
                                                             long observedAtMillis,
                                                             String reason) {
        return upsertSession(workerId, adapterId, routeKey, sessionToken, observedAtMillis, reason);
    }

    @Override
    public synchronized WorkerPresenceChange sessionHeartbeat(String workerId,
                                                             String adapterId,
                                                             String routeKey,
                                                             String sessionToken,
                                                             long observedAtMillis,
                                                             String reason) {
        return refreshSession(workerId, adapterId, routeKey, sessionToken, observedAtMillis, reason);
    }

    @Override
    public synchronized WorkerPresenceChange sessionDisconnected(String workerId,
                                                                String adapterId,
                                                                String routeKey,
                                                                String sessionToken,
                                                                long observedAtMillis,
                                                                String reason) {
        String normalizedWorkerId = requireText(workerId, "workerId");
        PresenceSessionKey key = new PresenceSessionKey(
                normalizedWorkerId,
                normalizeAdapterId(adapterId),
                requireText(sessionToken, "sessionToken")
        );
        long now = observedAtMillis > 0L ? observedAtMillis : clock.getAsLong();
        WorkerReachabilityState previous = stateForWorker(normalizedWorkerId, now);
        PresenceSessionRecord removed = activeSessions.remove(key);
        if (removed != null || seenWorkers.contains(normalizedWorkerId)) {
            seenWorkers.add(normalizedWorkerId);
        }
        WorkerReachabilityState current = stateForWorker(normalizedWorkerId, now);
        return new WorkerPresenceChange(
                normalizedWorkerId,
                previous,
                current,
                normalizeNullable(reason),
                previous != current,
                removed != null
        );
    }

    @Override
    public synchronized WorkerReachabilityState getWorkerReachability(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            return WorkerReachabilityState.UNKNOWN;
        }
        return stateForWorker(workerId.trim(), clock.getAsLong());
    }

    @Override
    public synchronized void setDispatchWakeupCallback(Runnable dispatchWakeupCallback) {
        this.dispatchWakeupCallback = dispatchWakeupCallback != null ? dispatchWakeupCallback : () -> {
        };
    }

    public synchronized int activeSessionCount(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            return 0;
        }
        String normalizedWorkerId = workerId.trim();
        pruneExpired(clock.getAsLong());
        int count = 0;
        for (PresenceSessionKey key : activeSessions.keySet()) {
            if (normalizedWorkerId.equals(key.workerId())) {
                count++;
            }
        }
        return count;
    }

    private WorkerPresenceChange upsertSession(String workerId,
                                               String adapterId,
                                               String routeKey,
                                               String sessionToken,
                                               long observedAtMillis,
                                               String reason) {
        String normalizedWorkerId = requireText(workerId, "workerId");
        PresenceSessionKey key = new PresenceSessionKey(
                normalizedWorkerId,
                normalizeAdapterId(adapterId),
                requireText(sessionToken, "sessionToken")
        );
        long now = observedAtMillis > 0L ? observedAtMillis : clock.getAsLong();
        WorkerReachabilityState previous = stateForWorker(normalizedWorkerId, now);
        seenWorkers.add(normalizedWorkerId);
        activeSessions.put(key, new PresenceSessionRecord(
                key,
                normalizeNullable(routeKey),
                now,
                normalizeNullable(reason)
        ));
        WorkerReachabilityState current = stateForWorker(normalizedWorkerId, now);
        WorkerPresenceChange change = new WorkerPresenceChange(
                normalizedWorkerId,
                previous,
                current,
                normalizeNullable(reason),
                previous != current,
                true
        );
        if (change.becameReachable()) {
            notifyDispatchWakeup();
        }
        return change;
    }

    private WorkerPresenceChange refreshSession(String workerId,
                                                String adapterId,
                                                String routeKey,
                                                String sessionToken,
                                                long observedAtMillis,
                                                String reason) {
        String normalizedWorkerId = requireText(workerId, "workerId");
        PresenceSessionKey key = new PresenceSessionKey(
                normalizedWorkerId,
                normalizeAdapterId(adapterId),
                requireText(sessionToken, "sessionToken")
        );
        long now = observedAtMillis > 0L ? observedAtMillis : clock.getAsLong();
        WorkerReachabilityState previous = stateForWorker(normalizedWorkerId, now);
        if (!activeSessions.containsKey(key)) {
            return new WorkerPresenceChange(
                    normalizedWorkerId,
                    previous,
                    previous,
                    normalizeNullable(reason),
                    false,
                    false
            );
        }
        seenWorkers.add(normalizedWorkerId);
        activeSessions.put(key, new PresenceSessionRecord(
                key,
                normalizeNullable(routeKey),
                now,
                normalizeNullable(reason)
        ));
        WorkerReachabilityState current = stateForWorker(normalizedWorkerId, now);
        return new WorkerPresenceChange(
                normalizedWorkerId,
                previous,
                current,
                normalizeNullable(reason),
                previous != current,
                true
        );
    }

    private WorkerReachabilityState stateForWorker(String workerId, long now) {
        pruneExpired(now);
        for (PresenceSessionKey key : activeSessions.keySet()) {
            if (workerId.equals(key.workerId())) {
                return WorkerReachabilityState.ONLINE;
            }
        }
        return seenWorkers.contains(workerId)
                ? WorkerReachabilityState.OFFLINE
                : WorkerReachabilityState.UNKNOWN;
    }

    private void pruneExpired(long now) {
        if (sessionTimeoutMillis == Long.MAX_VALUE) {
            return;
        }
        Iterator<Map.Entry<PresenceSessionKey, PresenceSessionRecord>> iterator =
                activeSessions.entrySet().iterator();
        while (iterator.hasNext()) {
            PresenceSessionRecord record = iterator.next().getValue();
            if (record.lastObservedAtMillis() + sessionTimeoutMillis < now) {
                seenWorkers.add(record.key().workerId());
                iterator.remove();
            }
        }
    }

    private void notifyDispatchWakeup() {
        try {
            dispatchWakeupCallback.run();
        } catch (RuntimeException ignored) {
            // Presence should not fail because dispatch wakeup notification failed.
        }
    }

    private static String normalizeAdapterId(String value) {
        return requireText(value, "adapterId").toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record PresenceSessionKey(String workerId, String adapterId, String sessionToken) {
    }

    private record PresenceSessionRecord(PresenceSessionKey key,
                                         String routeKey,
                                         long lastObservedAtMillis,
                                         String reason) {
    }
}
