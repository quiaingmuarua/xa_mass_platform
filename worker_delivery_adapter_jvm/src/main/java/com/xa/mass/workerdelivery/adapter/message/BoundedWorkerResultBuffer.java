package com.xa.mass.workerdelivery.adapter.message;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

public final class BoundedWorkerResultBuffer {

    private final int capacity;
    private final ArrayDeque<SeedResult> results = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private boolean accepting = true;

    public BoundedWorkerResultBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException(
                    "capacity must be positive"
            );
        }
        this.capacity = capacity;
    }

    public OfferStatus offer(SeedResult result) {
        Objects.requireNonNull(result, "result");
        lock.lock();
        try {
            if (!accepting) {
                return OfferStatus.CLOSED;
            }
            if (results.size() >= capacity) {
                return OfferStatus.FULL;
            }
            results.addLast(result);
            return OfferStatus.ACCEPTED;
        } finally {
            lock.unlock();
        }
    }

    public List<SeedResult> drain(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "limit must be positive"
            );
        }
        lock.lock();
        try {
            List<SeedResult> drained = new ArrayList<>(
                    Math.min(limit, results.size())
            );
            while (drained.size() < limit && !results.isEmpty()) {
                drained.add(results.removeFirst());
            }
            return List.copyOf(drained);
        } finally {
            lock.unlock();
        }
    }

    public void stopAccepting() {
        lock.lock();
        try {
            accepting = false;
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        lock.lock();
        try {
            return results.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    public enum OfferStatus {
        ACCEPTED,
        FULL,
        CLOSED
    }
}
