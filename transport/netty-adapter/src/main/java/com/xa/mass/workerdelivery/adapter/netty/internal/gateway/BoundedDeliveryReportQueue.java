package com.xa.mass.workerdelivery.adapter.netty.internal.gateway;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class BoundedDeliveryReportQueue {

    private final int capacity;
    private final ArrayDeque<String> reports = new ArrayDeque<>();
    private boolean accepting = true;

    public BoundedDeliveryReportQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public synchronized OfferStatus offer(String encodedDeliveryReport) {
        if (encodedDeliveryReport == null || encodedDeliveryReport.isEmpty()) {
            throw new IllegalArgumentException(
                    "encodedDeliveryReport must be non-empty"
            );
        }
        if (!accepting) {
            return OfferStatus.CLOSED;
        }
        if (reports.size() >= capacity) {
            return OfferStatus.FULL;
        }
        reports.addLast(encodedDeliveryReport);
        return OfferStatus.ACCEPTED;
    }

    synchronized List<String> drain() {
        ArrayList<String> drained = new ArrayList<>();
        while (!reports.isEmpty()) {
            drained.add(reports.removeFirst());
        }
        return List.copyOf(drained);
    }

    synchronized void stopAccepting() {
        accepting = false;
    }

    synchronized boolean isEmpty() {
        return reports.isEmpty();
    }

    public enum OfferStatus {
        ACCEPTED,
        FULL,
        CLOSED
    }
}
