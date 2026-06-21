package com.xa.mass.worker.runtime.presence;

import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;
import com.xa.mass.worker.runtime.evidence.SelectedWorkerDeliveryTargetEvidence;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * In-memory worker presence projection.
 *
 * <p>Presence identity is {@code workerId + adapterId + sessionToken}. The
 * adapter mailbox key is the delivery target projection. Route key is retained
 * as diagnostic metadata only and never participates in currentness decisions.</p>
 */
public final class InMemoryWorkerPresenceRuntime implements WorkerPresenceRuntime {

    public static final long DEFAULT_SESSION_TIMEOUT_MILLIS = 300_000L;

    private final long sessionTimeoutMillis;
    private final LongSupplier clock;
    private final Map<PresenceSessionKey, PresenceSessionRecord> activeSessions = new HashMap<>();
    private final Set<String> seenWorkers = new HashSet<>();
    private final Map<String, String> currentMailboxByWorker = new HashMap<>();
    private final Map<String, Long> deliveryTargetGenerationByWorker = new HashMap<>();
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
                                                             String adapterMailboxKey,
                                                             String routeKey,
                                                             String sessionToken,
                                                             long observedAtMillis,
                                                             String reason) {
        return upsertSession(workerId, adapterId, adapterMailboxKey, routeKey, sessionToken, observedAtMillis, reason);
    }

    @Override
    public synchronized WorkerPresenceChange sessionHeartbeat(String workerId,
                                                             String adapterId,
                                                             String adapterMailboxKey,
                                                             String routeKey,
                                                             String sessionToken,
                                                             long observedAtMillis,
                                                             String reason) {
        return refreshSession(workerId, adapterId, adapterMailboxKey, routeKey, sessionToken, observedAtMillis, reason);
    }

    @Override
    public synchronized WorkerPresenceChange sessionDisconnected(String workerId,
                                                                String adapterId,
                                                                String adapterMailboxKey,
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
    public synchronized Optional<SelectedWorkerDeliveryTargetEvidence> resolveDeliveryTarget(String selectedWorkerId) {
        if (selectedWorkerId == null || selectedWorkerId.isBlank()) {
            return Optional.empty();
        }
        long now = clock.getAsLong();
        String normalizedWorkerId = selectedWorkerId.trim();
        WorkerReachabilityState reachability = stateForWorker(normalizedWorkerId, now);
        PresenceSessionRecord session = currentSessionForWorker(normalizedWorkerId, now);
        if (session == null) {
            return Optional.empty();
        }
        String mailboxKey = session.adapterMailboxKey();
        long generation = ensureDeliveryTargetGeneration(normalizedWorkerId, mailboxKey);
        long expiresAt = sessionTimeoutMillis == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : session.lastObservedAtMillis() + sessionTimeoutMillis;
        return Optional.of(new SelectedWorkerDeliveryTargetEvidence(
                normalizedWorkerId,
                mailboxKey,
                reachability,
                generation,
                session.lastObservedAtMillis(),
                expiresAt
        ));
    }

    @Override
    public synchronized void setDispatchWakeupCallback(Runnable dispatchWakeupCallback) {
        this.dispatchWakeupCallback = dispatchWakeupCallback != null ? dispatchWakeupCallback : () -> {
        };
    }

    private WorkerPresenceChange upsertSession(String workerId,
                                               String adapterId,
                                               String adapterMailboxKey,
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
        String normalizedMailboxKey = requireText(adapterMailboxKey, "adapterMailboxKey");
        WorkerReachabilityState previous = stateForWorker(normalizedWorkerId, now);
        seenWorkers.add(normalizedWorkerId);
        activeSessions.put(key, new PresenceSessionRecord(
                key,
                normalizedMailboxKey,
                normalizeNullable(routeKey),
                now,
                normalizeNullable(reason)
        ));
        ensureDeliveryTargetGeneration(normalizedWorkerId, normalizedMailboxKey);
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
                                                String adapterMailboxKey,
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
        String normalizedMailboxKey = requireText(adapterMailboxKey, "adapterMailboxKey");
        activeSessions.put(key, new PresenceSessionRecord(
                key,
                normalizedMailboxKey,
                normalizeNullable(routeKey),
                now,
                normalizeNullable(reason)
        ));
        ensureDeliveryTargetGeneration(normalizedWorkerId, normalizedMailboxKey);
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

    private PresenceSessionRecord currentSessionForWorker(String workerId, long now) {
        pruneExpired(now);
        PresenceSessionRecord selected = null;
        for (PresenceSessionRecord record : activeSessions.values()) {
            if (!workerId.equals(record.key().workerId())) {
                continue;
            }
            if (selected == null || record.lastObservedAtMillis() > selected.lastObservedAtMillis()) {
                selected = record;
            }
        }
        return selected;
    }

    private long ensureDeliveryTargetGeneration(String workerId, String mailboxKey) {
        String previous = currentMailboxByWorker.get(workerId);
        if (previous == null) {
            currentMailboxByWorker.put(workerId, mailboxKey);
            long generation = Math.max(1L, deliveryTargetGenerationByWorker.getOrDefault(workerId, 0L));
            deliveryTargetGenerationByWorker.put(workerId, generation);
            return generation;
        }
        long generation = deliveryTargetGenerationByWorker.getOrDefault(workerId, 1L);
        if (!previous.equals(mailboxKey)) {
            generation++;
            currentMailboxByWorker.put(workerId, mailboxKey);
            deliveryTargetGenerationByWorker.put(workerId, generation);
        }
        return generation;
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
                                         String adapterMailboxKey,
                                         String routeKey,
                                         long lastObservedAtMillis,
                                         String reason) {
    }
}
