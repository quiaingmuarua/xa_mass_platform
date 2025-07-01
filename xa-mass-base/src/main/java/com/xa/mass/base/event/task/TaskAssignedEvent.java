package com.xa.mass.base.event.task;

import com.xa.mass.base.model.Task;

public class TaskAssignedEvent {
    private final Task task;
    public TaskAssignedEvent(Task task) { this.task = task; }
    public Task getTask() { return task; }
} 