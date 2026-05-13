package com.xa.mass.sdk.model;

import java.util.List;

public final class TaskResultWindowSnapshot {
    private final String taskId;
    private final List<TaskResultItemSnapshot> items;
    private final long nextAfterSeq;
    private final boolean hasMore;
    private final long totalVisible;

    public TaskResultWindowSnapshot(String taskId,
                                    List<TaskResultItemSnapshot> items,
                                    long nextAfterSeq,
                                    boolean hasMore,
                                    long totalVisible) {
        this.taskId = taskId;
        this.items = items == null ? List.of() : List.copyOf(items);
        this.nextAfterSeq = nextAfterSeq;
        this.hasMore = hasMore;
        this.totalVisible = totalVisible;
    }

    public String getTaskId() { return taskId; }
    public List<TaskResultItemSnapshot> getItems() { return items; }
    public long getNextAfterSeq() { return nextAfterSeq; }
    public boolean isHasMore() { return hasMore; }
    public long getTotalVisible() { return totalVisible; }
}
