package com.xa.mass.kernel.assignment;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;

/** Process-local Worker Match Queue implementation. */
public final class InMemoryWorkerMatchQueue implements WorkerMatchQueue {

    private final ArrayBlockingQueue<TaskRuleMatchDemand> demands;

    public InMemoryWorkerMatchQueue(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        demands = new ArrayBlockingQueue<>(capacity);
    }

    @Override
    public boolean offer(TaskRuleMatchDemand demand) {
        return demands.offer(Objects.requireNonNull(demand, "demand"));
    }

    @Override
    public int size() {
        return demands.size();
    }

    @Override
    public TaskRuleMatchDemand consume() throws InterruptedException {
        return demands.take();
    }
}
