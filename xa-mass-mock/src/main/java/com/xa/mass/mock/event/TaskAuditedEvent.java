package com.xa.mass.mock.event;

import com.xa.mass.eventbus.model.Task;

public class TaskAuditedEvent {
    private final Task task;
    public TaskAuditedEvent(Task task) { this.task = task; }
    public Task getTask() { return task; }
} 