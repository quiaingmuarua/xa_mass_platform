package com.xa.mass.base.event.task;

import com.xa.mass.base.model.Task;

public class TaskAuditedEvent {
    private final Task task;
    public TaskAuditedEvent(Task task) { this.task = task; }
    public Task getTask() { return task; }
} 