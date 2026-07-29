package com.xa.mass.workerdelivery.adapter.result;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;

public final class BoundedWorkerResultQueue {

    private final ArrayBlockingQueue<SeedResult> results;
    private volatile boolean accepting = true;

    public BoundedWorkerResultQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException(
                    "capacity must be positive"
            );
        }
        results = new ArrayBlockingQueue<>(capacity);
    }

    public synchronized OfferStatus offer(SeedResult result) {
        Objects.requireNonNull(result, "result");
        if (!accepting) {
            return OfferStatus.CLOSED;
        }
        return results.offer(result)
                ? OfferStatus.ACCEPTED
                : OfferStatus.FULL;
    }

    public List<SeedResult> drainAll() {
        ArrayList<SeedResult> drained = new ArrayList<>(results.size());
        results.drainTo(drained);
        return List.copyOf(drained);
    }

    public synchronized void stopAccepting() {
        accepting = false;
    }

    public boolean isEmpty() {
        return results.isEmpty();
    }

    public enum OfferStatus {
        ACCEPTED,
        FULL,
        CLOSED
    }
}
