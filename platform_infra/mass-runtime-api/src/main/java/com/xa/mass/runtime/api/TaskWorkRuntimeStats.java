package com.xa.mass.runtime.api;

public record TaskWorkRuntimeStats(long readyItems,
                                   long inflightItems,
                                   long delayedItems,
                                   int taskQueueCount,
                                   int maxQueuedItems,
                                   long oldestReadyAgeMillis,
                                   long enqueuedItems,
                                   long claimedItems,
                                   long resultAppliedItems,
                                   long backpressureRejectedItems,
                                   long duplicateResultItems,
                                   long staleResultItems,
                                   long expiredLeaseItems,
                                   long discardedItems,
                                   long shutdownClearedItems) {
}

