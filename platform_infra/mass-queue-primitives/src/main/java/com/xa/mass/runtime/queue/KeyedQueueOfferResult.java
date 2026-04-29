package com.xa.mass.runtime.queue;

public record KeyedQueueOfferResult(Status status, String reason) {

    public enum Status {
        ENQUEUED,
        BACKPRESSURE_REJECTED,
        INVALID,
        UNAVAILABLE
    }

    public KeyedQueueOfferResult {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
    }

    public static KeyedQueueOfferResult enqueued() {
        return new KeyedQueueOfferResult(Status.ENQUEUED, null);
    }

    public static KeyedQueueOfferResult backpressureRejected(String reason) {
        return new KeyedQueueOfferResult(Status.BACKPRESSURE_REJECTED, reason);
    }

    public static KeyedQueueOfferResult invalid(String reason) {
        return new KeyedQueueOfferResult(Status.INVALID, reason);
    }

    public static KeyedQueueOfferResult unavailable(String reason) {
        return new KeyedQueueOfferResult(Status.UNAVAILABLE, reason);
    }
}
