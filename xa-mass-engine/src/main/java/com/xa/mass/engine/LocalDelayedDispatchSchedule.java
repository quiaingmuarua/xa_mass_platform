package com.xa.mass.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM-local implementation of {@link DelayedDispatchSchedule} backed by a
 * {@link ConcurrentHashMap}. All operations are lock-free and atomic per key.
 */
final class LocalDelayedDispatchSchedule implements DelayedDispatchSchedule {

    private final Map<String, Long> store = new ConcurrentHashMap<>();

    @Override
    public Long getDueAt(String taskId) {
        return store.get(taskId);
    }

    @Override
    public boolean insertIfAbsent(String taskId, long dueAtMillis) {
        return store.putIfAbsent(taskId, dueAtMillis) == null;
    }

    @Override
    public boolean replaceIfEqual(String taskId, long expectedDueAt, long newDueAt) {
        return store.replace(taskId, expectedDueAt, newDueAt);
    }

    @Override
    public void remove(String taskId) {
        store.remove(taskId);
    }

    @Override
    public boolean removeIfEqual(String taskId, long expectedDueAt) {
        return store.remove(taskId, expectedDueAt);
    }

    @Override
    public void clear() {
        store.clear();
    }
}
