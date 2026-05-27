package com.xa.mass.engine.worker;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded task-local candidate hint state.
 *
 * <p>This owner stores only observed candidate source evidence. It does not
 * decide eligibility, reserve capacity, hold locks, or own dispatch truth.</p>
 */
final class TaskCandidateWarmPool {

    static final int DEFAULT_PER_TASK_CAP = 128;
    static final int DEFAULT_GLOBAL_CAP = 10_000;
    static final long DEFAULT_TTL_MILLIS = 60_000L;

    private final int perTaskCap;
    private final int globalCap;
    private final long ttlMillis;
    private final ConcurrentMap<String, ConcurrentLinkedDeque<Entry>> entriesByTaskId = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<Entry> globalOrder = new ConcurrentLinkedDeque<>();
    private final AtomicInteger globalCount = new AtomicInteger();

    TaskCandidateWarmPool() {
        this(DEFAULT_PER_TASK_CAP, DEFAULT_GLOBAL_CAP, DEFAULT_TTL_MILLIS);
    }

    TaskCandidateWarmPool(int perTaskCap, int globalCap, long ttlMillis) {
        this.perTaskCap = Math.max(1, perTaskCap);
        this.globalCap = Math.max(this.perTaskCap, globalCap);
        this.ttlMillis = Math.max(1L, ttlMillis);
    }

    void put(Entry entry) {
        if (entry == null) {
            return;
        }
        ConcurrentLinkedDeque<Entry> taskEntries = entriesByTaskId.computeIfAbsent(
                entry.taskId(),
                ignored -> new ConcurrentLinkedDeque<>()
        );
        taskEntries.addLast(entry);
        globalOrder.addLast(entry);
        globalCount.incrementAndGet();
        trimTask(taskEntries);
        trimGlobal();
    }

    List<Entry> sample(String taskId, long nowMillis, int maxCount) {
        String normalizedTaskId = normalizeNullable(taskId);
        if (normalizedTaskId == null || maxCount <= 0) {
            return List.of();
        }
        ConcurrentLinkedDeque<Entry> taskEntries = entriesByTaskId.get(normalizedTaskId);
        if (taskEntries == null || taskEntries.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> seenWorkerIds = new LinkedHashSet<>();
        List<Entry> sampled = new ArrayList<>(Math.min(maxCount, perTaskCap));
        Iterator<Entry> iterator = taskEntries.descendingIterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry == null) {
                continue;
            }
            if (isExpired(entry, nowMillis)) {
                remove(entry);
                continue;
            }
            if (!seenWorkerIds.add(entry.workerId())) {
                continue;
            }
            sampled.add(entry);
            if (sampled.size() >= maxCount) {
                break;
            }
        }
        return List.copyOf(sampled);
    }

    void remove(Entry entry) {
        if (entry == null) {
            return;
        }
        ConcurrentLinkedDeque<Entry> taskEntries = entriesByTaskId.get(entry.taskId());
        if (taskEntries != null && taskEntries.remove(entry)) {
            globalCount.updateAndGet(current -> current > 0 ? current - 1 : 0);
        }
        globalOrder.remove(entry);
    }

    int sizeForTask(String taskId) {
        ConcurrentLinkedDeque<Entry> taskEntries = entriesByTaskId.get(normalizeNullable(taskId));
        return taskEntries == null ? 0 : taskEntries.size();
    }

    private void trimTask(ConcurrentLinkedDeque<Entry> taskEntries) {
        while (taskEntries.size() > perTaskCap) {
            Entry removed = taskEntries.pollFirst();
            if (removed == null) {
                return;
            }
            globalOrder.remove(removed);
            globalCount.updateAndGet(current -> current > 0 ? current - 1 : 0);
        }
    }

    private void trimGlobal() {
        while (globalCount.get() > globalCap) {
            Entry removed = globalOrder.pollFirst();
            if (removed == null) {
                globalCount.set(0);
                return;
            }
            ConcurrentLinkedDeque<Entry> taskEntries = entriesByTaskId.get(removed.taskId());
            if (taskEntries != null && taskEntries.remove(removed)) {
                globalCount.updateAndGet(current -> current > 0 ? current - 1 : 0);
            }
        }
    }

    private boolean isExpired(Entry entry, long nowMillis) {
        return entry.observedAtMillis() + ttlMillis <= nowMillis;
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    record Entry(String taskId,
                 String workerId,
                 String observedGroupId,
                 String observedAdapterNodeId,
                 String observedRouteBucketKey,
                 long observedAtMillis) {
        Entry {
            taskId = requireNonBlank(taskId, "taskId");
            workerId = requireNonBlank(workerId, "workerId");
            observedGroupId = requireNonBlank(observedGroupId, "observedGroupId");
            observedAdapterNodeId = normalizeNullable(observedAdapterNodeId);
            observedRouteBucketKey = requireNonBlank(observedRouteBucketKey, "observedRouteBucketKey");
        }

        private static String requireNonBlank(String value, String fieldName) {
            String normalized = normalizeNullable(value);
            if (normalized == null) {
                throw new IllegalArgumentException(fieldName + " must not be blank");
            }
            return Objects.requireNonNull(normalized);
        }
    }
}
