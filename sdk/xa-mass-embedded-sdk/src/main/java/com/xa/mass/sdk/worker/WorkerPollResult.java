package com.xa.mass.sdk.worker;

import java.util.List;
import java.util.Objects;

public final class WorkerPollResult {

    private final WorkerPollStatus status;
    private final List<WorkerInvocation> items;

    private WorkerPollResult(WorkerPollStatus status, List<WorkerInvocation> items) {
        this.status = Objects.requireNonNull(status, "status");
        this.items = items == null || items.isEmpty() ? List.of() : List.copyOf(items);
    }

    public static WorkerPollResult of(WorkerPollStatus status, List<WorkerInvocation> items) {
        Objects.requireNonNull(status, "status");
        return switch (status) {
            case DELIVERED -> delivered(items);
            case EMPTY -> empty();
            case INVALID_REQUEST -> invalidRequest();
            case UNAVAILABLE -> unavailable();
            case SHUTDOWN -> shutdown();
        };
    }

    public static WorkerPollResult delivered(List<WorkerInvocation> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("delivered poll result must include at least one item");
        }
        return new WorkerPollResult(WorkerPollStatus.DELIVERED, items);
    }

    public static WorkerPollResult empty() {
        return new WorkerPollResult(WorkerPollStatus.EMPTY, List.of());
    }

    public static WorkerPollResult invalidRequest() {
        return new WorkerPollResult(WorkerPollStatus.INVALID_REQUEST, List.of());
    }

    public static WorkerPollResult unavailable() {
        return new WorkerPollResult(WorkerPollStatus.UNAVAILABLE, List.of());
    }

    public static WorkerPollResult shutdown() {
        return new WorkerPollResult(WorkerPollStatus.SHUTDOWN, List.of());
    }

    public WorkerPollStatus getStatus() {
        return status;
    }

    public List<WorkerInvocation> getItems() {
        return items;
    }
}
