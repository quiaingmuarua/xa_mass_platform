package com.xa.mass.engine;

/**
 * Pluggable registry for coalesced delayed task-dispatch wakeup timestamps.
 *
 * <p>Each task maps to at most one pending wakeup deadline. All mutating
 * operations are CAS-based so implementations must provide atomicity per key.
 *
 * <p>The default {@link LocalDelayedDispatchSchedule} keeps state in a
 * JVM-local {@link java.util.concurrent.ConcurrentHashMap}. Replace with a
 * distributed store (e.g. Redis sorted set, Hazelcast IMap) when the engine
 * runs across multiple nodes.
 */
interface DelayedDispatchSchedule {

    /** Returns the registered wakeup timestamp for {@code taskId}, or {@code null} if none. */
    Long getDueAt(String taskId);

    /**
     * Registers {@code dueAtMillis} for {@code taskId} only if no entry exists.
     *
     * @return {@code true} if the entry was inserted (key was absent)
     */
    boolean insertIfAbsent(String taskId, long dueAtMillis);

    /**
     * Replaces the entry for {@code taskId} only if its current value equals
     * {@code expectedDueAt}.
     *
     * @return {@code true} if the value was replaced
     */
    boolean replaceIfEqual(String taskId, long expectedDueAt, long newDueAt);

    /** Unconditionally removes the entry for {@code taskId}. */
    void remove(String taskId);

    /**
     * Removes the entry for {@code taskId} only if its current value equals
     * {@code expectedDueAt}.
     *
     * @return {@code true} if the entry was removed
     */
    boolean removeIfEqual(String taskId, long expectedDueAt);

    /** Clears all entries (called on engine shutdown). */
    void clear();
}
