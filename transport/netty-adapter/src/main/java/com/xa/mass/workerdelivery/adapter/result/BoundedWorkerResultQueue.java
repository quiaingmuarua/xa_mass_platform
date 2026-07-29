package com.xa.mass.workerdelivery.adapter.result;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class BoundedWorkerResultQueue {

    private final int capacity;
    private final ArrayDeque<QueuedResult> results = new ArrayDeque<>();
    private boolean accepting = true;

    public BoundedWorkerResultQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException(
                    "capacity must be positive"
            );
        }
        this.capacity = capacity;
    }

    public synchronized OfferStatus offer(
            SeedResultSource source,
            String encodedSeedResult
    ) {
        Objects.requireNonNull(source, "source");
        if (encodedSeedResult == null || encodedSeedResult.isEmpty()) {
            throw new IllegalArgumentException(
                    "encodedSeedResult must be non-empty"
            );
        }
        if (!accepting) {
            return OfferStatus.CLOSED;
        }
        if (results.size() >= capacity) {
            return OfferStatus.FULL;
        }
        results.addLast(new QueuedResult(source, encodedSeedResult));
        return OfferStatus.ACCEPTED;
    }

    synchronized List<String> drain(SeedResultSource source) {
        Objects.requireNonNull(source, "source");
        ArrayList<String> drained = new ArrayList<>();
        int observed = results.size();
        for (int index = 0; index < observed; index++) {
            QueuedResult result = results.removeFirst();
            if (result.source == source) {
                drained.add(result.encodedSeedResult);
            } else {
                results.addLast(result);
            }
        }
        return List.copyOf(drained);
    }

    public synchronized void stopAccepting() {
        accepting = false;
    }

    public synchronized boolean isEmpty() {
        return results.isEmpty();
    }

    private static final class QueuedResult {

        private final SeedResultSource source;
        private final String encodedSeedResult;

        private QueuedResult(
                SeedResultSource source,
                String encodedSeedResult
        ) {
            this.source = source;
            this.encodedSeedResult = encodedSeedResult;
        }
    }

    public enum OfferStatus {
        ACCEPTED,
        FULL,
        CLOSED
    }
}
