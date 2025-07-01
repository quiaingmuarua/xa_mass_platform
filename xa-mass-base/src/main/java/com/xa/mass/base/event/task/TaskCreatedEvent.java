package com.xa.mass.base.event.task;

import com.xa.mass.base.model.Task;

public class TaskCreatedEvent {
    private final Task task;
    public TaskCreatedEvent(Task task) { this.task = task; }
    public Task getTask() { return task; }
} 