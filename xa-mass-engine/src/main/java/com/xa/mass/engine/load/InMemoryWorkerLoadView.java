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
    public void recordWorkClaimed(String workerId, String taskId) {
        if (workerId == null || workerId.isBlank()) {
            return;
        }
        activeLeaseCounts.computeIfAbsent(workerId, ignored -> new AtomicInteger()).incrementAndGet();
    }

    @Override
    public void recordWorkFinal(String workerId, String taskId) {
        if (workerId == null || workerId.isBlank()) {
            return;
        }
        activeLeaseCounts.computeIfPresent(workerId, (ignored, current) -> {
            current.updateAndGet(value -> Math.max(0, value - 1));
            return current;
        });
    }
}
