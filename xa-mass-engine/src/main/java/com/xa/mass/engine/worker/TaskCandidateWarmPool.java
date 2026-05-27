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
import java.util.concurrent.atomic.AtomicReference;

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
        AtomicReference<ConcurrentLinkedDeque<Entry>> taskEntriesRef = new AtomicReference<>();
        entriesByTaskId.compute(entry.taskId(), (ignored, existing) -> {
            ConcurrentLinkedDeque<Entry> taskEntries = existing != null ? existing : new ConcurrentLinkedDeque<>();
            taskEntries.addLast(entry);
            taskEntriesRef.set(taskEntries);
            return taskEntries;
        });
        globalOrder.addLast(entry);
        globalCount.incrementAndGet();
        trimTask(entry.taskId(), taskEntriesRef.get());
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
        AtomicInteger removed = new AtomicInteger();
        entriesByTaskId.computeIfPresent(entry.taskId(), (ignored, taskEntries) -> {
            if (taskEntries.remove(entry)) {
                removed.incrementAndGet();
            }
            return taskEntries.isEmpty() ? null : taskEntries;
        });
        if (removed.get() > 0) {
            globalCount.updateAndGet(current -> current > 0 ? current - 1 : 0);
        }
        globalOrder.remove(entry);
    }

    int sizeForTask(String taskId) {
        ConcurrentLinkedDeque<Entry> taskEntries = entriesByTaskId.get(normalizeNullable(taskId));
        return taskEntries == null ? 0 : taskEntries.size();
    }

    int trackedTaskCount() {
        return entriesByTaskId.size();
    }

    private void trimTask(String taskId, ConcurrentLinkedDeque<Entry> taskEntries) {
        if (taskEntries == null) {
            return;
        }
        while (taskEntries.size() > perTaskCap) {
            Entry removed = taskEntries.pollFirst();
            if (removed == null) {
                return;
            }
            globalOrder.remove(removed);
            globalCount.updateAndGet(current -> current > 0 ? current - 1 : 0);
        }
        removeTaskIfEmpty(taskId, taskEntries);
    }

    private void trimGlobal() {
        while (globalCount.get() > globalCap) {
            Entry removed = globalOrder.pollFirst();
            if (removed == null) {
                globalCount.set(0);
                return;
            }
            AtomicInteger removedFromTask = new AtomicInteger();
            entriesByTaskId.computeIfPresent(removed.taskId(), (ignored, taskEntries) -> {
                if (taskEntries.remove(removed)) {
                    removedFromTask.incrementAndGet();
                }
                return taskEntries.isEmpty() ? null : taskEntries;
            });
            if (removedFromTask.get() > 0) {
                globalCount.updateAndGet(current -> current > 0 ? current - 1 : 0);
            }
        }
    }

    private void removeTaskIfEmpty(String taskId, ConcurrentLinkedDeque<Entry> taskEntries) {
        if (taskEntries == null) {
            return;
        }
        entriesByTaskId.computeIfPresent(taskId, (ignored, current) ->
                current == taskEntries && current.isEmpty() ? null : current);
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
