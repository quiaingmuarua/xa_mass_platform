package com.xa.mass.transport.channel;

import com.xa.mass.transport.model.TaskDispatchItem;

import java.util.List;
import java.util.Objects;

public final class TaskPullResult {

    private final TaskPullStatus status;
    private final List<TaskDispatchItem> items;

    private TaskPullResult(TaskPullStatus status, List<TaskDispatchItem> items) {
        this.status = Objects.requireNonNull(status, "status");
        this.items = items == null || items.isEmpty() ? List.of() : List.copyOf(items);
    }

    public static TaskPullResult of(TaskPullStatus status, List<TaskDispatchItem> items) {
        Objects.requireNonNull(status, "status");
        return switch (status) {
            case DELIVERED -> delivered(items);
            case EMPTY -> empty();
            case INVALID_REQUEST -> invalidRequest();
            case UNAVAILABLE -> unavailable();
            case SHUTDOWN -> shutdown();
        };
    }

    public static TaskPullResult delivered(List<TaskDispatchItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("delivered pull result must include at least one item");
        }
        return new TaskPullResult(TaskPullStatus.DELIVERED, items);
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

    public List<TaskDispatchItem> getItems() {
        return items;
    }
}
