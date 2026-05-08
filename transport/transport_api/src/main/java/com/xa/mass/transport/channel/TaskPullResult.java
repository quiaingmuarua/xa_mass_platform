package com.xa.mass.transport.channel;

import com.xa.mass.transport.model.TaskDispatchItem;

import java.util.List;
import java.util.Objects;

public final class TaskPullResult {

    private final TaskPullStatus status;
    private final List<TaskDispatchItem> dispatchViews;

    private TaskPullResult(TaskPullStatus status, List<TaskDispatchItem> dispatchViews) {
        this.status = Objects.requireNonNull(status, "status");
        this.dispatchViews = dispatchViews == null || dispatchViews.isEmpty() ? List.of() : List.copyOf(dispatchViews);
    }

    public static TaskPullResult of(TaskPullStatus status, List<TaskDispatchItem> dispatchViews) {
        Objects.requireNonNull(status, "status");
        return switch (status) {
            case DELIVERED -> delivered(dispatchViews);
            case EMPTY -> empty();
            case INVALID_REQUEST -> invalidRequest();
            case UNAVAILABLE -> unavailable();
            case SHUTDOWN -> shutdown();
        };
    }

    public static TaskPullResult delivered(List<TaskDispatchItem> dispatchViews) {
        if (dispatchViews == null || dispatchViews.isEmpty()) {
            throw new IllegalArgumentException("delivered pull result must include at least one item");
        }
        return new TaskPullResult(TaskPullStatus.DELIVERED, dispatchViews);
    }

    public static TaskPullResult empty() {
        return new TaskPullResult(TaskPullStatus.EMPTY, List.of());
    }

    public static TaskPullResult invalidRequest() {
        return new TaskPullResult(TaskPullStatus.INVALID_REQUEST, List.of());
    }

    public static TaskPullResult unavailable() {
        return new TaskPullResult(TaskPullStatus.UNAVAILABLE, List.of());
    }

    public static TaskPullResult shutdown() {
        return new TaskPullResult(TaskPullStatus.SHUTDOWN, List.of());
    }

    public TaskPullStatus getStatus() {
        return status;
    }

    /**
     * Worker-facing dispatch views reconstructed from transport packets when
     * delivery succeeds.
     */
    public List<TaskDispatchItem> getDispatchViews() {
        return dispatchViews;
    }
}
