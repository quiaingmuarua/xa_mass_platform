package com.xa.mass.workerdelivery.adapter.result;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class BoundedWorkerResultQueue {

    private final int capacity;
    private final ArrayDeque<String> results = new ArrayDeque<>();
    private boolean accepting = true;

    public BoundedWorkerResultQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException(
                    "capacity must be positive"
            );
        }
        this.capacity = capacity;
    }

    public synchronized OfferStatus offer(String encodedWorkerResult) {
        if (encodedWorkerResult == null || encodedWorkerResult.isEmpty()) {
            throw new IllegalArgumentException(
                    "encodedWorkerResult must be non-empty"
            );
        }
        if (!accepting) {
            return OfferStatus.CLOSED;
        }
        if (results.size() >= capacity) {
            return OfferStatus.FULL;
        }
        results.addLast(encodedWorkerResult);
        return OfferStatus.ACCEPTED;
    }

    synchronized List<String> drain() {
        ArrayList<String> drained = new ArrayList<>();
        while (!results.isEmpty()) {
            drained.add(results.removeFirst());
        }
        return List.copyOf(drained);
    }

    public synchronized void stopAccepting() {
        accepting = false;
    }

    public synchronized boolean isEmpty() {
        return results.isEmpty();
    }

    public enum OfferStatus {
        ACCEPTED,
        FULL,
        CLOSED
    }
}
