package com.xa.mass.engine.load;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory worker load snapshot maintained by runtime lifecycle callbacks.
 */
public final class InMemoryWorkerLoadView implements WorkerLoadView {

    private final ConcurrentMap<String, AtomicInteger> activeLeaseCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> reservedCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> declaredCapacities = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<String, AtomicInteger>> activeWorkerCountsByTask =
            new ConcurrentHashMap<>();

    @Override
    public int getActiveLeaseCount(String workerId) {
        AtomicInteger count = activeLeaseCounts.get(workerId);
        return count == null ? 0 : Math.max(0, count.get());
    }

    @Override
    public int getReservedCount(String workerId) {
        AtomicInteger count = reservedCounts.get(workerId);
        return count == null ? 0 : Math.max(0, count.get());
    }

    @Override
    public double getEstimatedLoadRatio(String workerId) {
        return snapshot(workerId).estimatedLoadRatio();
    }

    @Override
    public WorkerLoadSnapshot snapshot(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            return WorkerLoadSnapshot.empty(workerId);
        }
        return new WorkerLoadSnapshot(
                workerId,
                getActiveLeaseCount(workerId),
                getReservedCount(workerId),
                declaredCapacities.getOrDefault(workerId, 1)
        );
    }

    @Override
    public int getActiveWorkerCountForTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return 0;
        }
        ConcurrentMap<String, AtomicInteger> workerCounts = activeWorkerCountsByTask.get(taskId);
        if (workerCounts == null || workerCounts.isEmpty()) {
            return 0;
        }
        return (int) workerCounts.values().stream()
                .filter(count -> count != null && count.get() > 0)
                .count();
    }

    @Override
    public void recordDeclaredCapacity(String workerId, int declaredCapacity) {
        if (workerId == null || workerId.isBlank()) {
            return;
        }
        declaredCapacities.put(workerId, Math.max(1, declaredCapacity));
    }

    @Override
    public synchronized boolean tryReserveCapacity(String workerId, String taskId) {
        if (workerId == null || workerId.isBlank()) {
            return false;
        }
        int activeCount = getActiveLeaseCount(workerId);
        int reservedCount = getReservedCount(workerId);
        int declaredCapacity = declaredCapacities.getOrDefault(workerId, 1);
        if (activeCount + reservedCount >= Math.max(1, declaredCapacity)) {
            return false;
        }
        reservedCounts.computeIfAbsent(workerId, ignored -> new AtomicInteger()).incrementAndGet();
        return true;
    }

    @Override
    public synchronized boolean confirmReservation(String workerId, String taskId) {
        if (workerId == null || workerId.isBlank()) {
            return false;
        }
        AtomicInteger reserved = reservedCounts.get(workerId);
        if (reserved == null || reserved.get() <= 0) {
            return false;
        }
        reserved.updateAndGet(value -> Math.max(0, value - 1));
        activeLeaseCounts.computeIfAbsent(workerId, ignored -> new AtomicInteger()).incrementAndGet();
        incrementActiveWorkerForTask(workerId, taskId);
        return true;
    }

    @Override
    public synchronized void releaseReservation(String workerId, String taskId) {
        if (workerId == null || workerId.isBlank()) {
            return;
        }
        reservedCounts.computeIfPresent(workerId, (ignored, current) -> {
            current.updateAndGet(value -> Math.max(0, value - 1));
            return current;
        });
    }

    @Override
    public synchronized void recordWorkClaimed(String workerId, String taskId) {
        if (workerId == null || workerId.isBlank()) {
            return;
        }
        activeLeaseCounts.computeIfAbsent(workerId, ignored -> new AtomicInteger()).incrementAndGet();
        incrementActiveWorkerForTask(workerId, taskId);
    }

    @Override
    public synchronized void recordWorkFinal(String workerId, String taskId) {
        if (workerId == null || workerId.isBlank()) {
            return;
        }
        activeLeaseCounts.computeIfPresent(workerId, (ignored, current) -> {
            current.updateAndGet(value -> Math.max(0, value - 1));
            return current;
        });
        if (taskId != null && !taskId.isBlank()) {
            activeWorkerCountsByTask.computeIfPresent(taskId, (ignoredTaskId, workerCounts) -> {
                workerCounts.computeIfPresent(workerId, (ignoredWorkerId, current) -> {
                    current.updateAndGet(value -> Math.max(0, value - 1));
                    return current.get() <= 0 ? null : current;
                });
                return workerCounts.isEmpty() ? null : workerCounts;
            });
        }
    }

    private void incrementActiveWorkerForTask(String workerId, String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        activeWorkerCountsByTask
                .computeIfAbsent(taskId, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(workerId, ignored -> new AtomicInteger())
                .incrementAndGet();
    }
}
