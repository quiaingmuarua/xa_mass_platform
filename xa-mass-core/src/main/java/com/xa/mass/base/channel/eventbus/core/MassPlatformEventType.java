package com.xa.mass.base.channel.eventbus.core;

/**
 * Platform governance and observability event types.
 */
public enum MassPlatformEventType {
    TASK_REVIEW_RANDOM("Random task review"),
    TASK_REVIEW_DELAY("Delayed task review"),
    TASK_REVIEW_PARTIAL_FAILURE("Partial task failure"),
    TASK_REVIEW_MISAPPROVAL("Incorrect task approval"),

    TASK_CREATED("Task created"),
    TASK_AUDITED("Task approved"),
    TASK_ASSIGNED("Task assigned"),

    WORKER_OFFLINE_BATCH("Batch worker offline"),
    WORKER_OFFLINE_SINGLE("Single worker offline"),
    WORKER_FLASH_DISCONNECT("Worker transient disconnect"),
    WORKER_LONG_ABSENCE("Worker long absence"),
    WORKER_ONLINE_BATCH("Batch worker online"),

    WORKER_CONTEXT_INVALIDATION("WorkerContext invalidated"),
    WORKER_CONTEXT_RETRY_LOOP("WorkerContext retry loop"),
    WORKER_CONTEXT_BATCH_UNAVAILABLE("Batch WorkerContext unavailable"),
    MESSAGE_PROCESSING_ERROR("Message processing error"),

    TASK_ASSIGNMENT_CONFLICT("Task assignment conflict"),
    MESSAGE_DUPLICATE_ASSIGNMENT("Duplicate message assignment"),
    BATCH_ORDER_CHAOS("Batch order disorder"),

    RPC_TIMEOUT("RPC timeout"),
    MESSAGE_QUEUE_BLOCK("Message queue blocked"),
    NETWORK_LATENCY("Network latency"),

    LOGGING_FAILURE("Logging failure"),
    STATUS_REPORT_FAILURE("Status reporting failure"),
    METRICS_COLLECTION_FAILURE("Metrics collection failure");

    private final String description;

    MassPlatformEventType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
