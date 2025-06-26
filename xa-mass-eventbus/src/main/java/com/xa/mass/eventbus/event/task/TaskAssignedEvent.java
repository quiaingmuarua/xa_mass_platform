package com.xa.mass.eventbus.event.task;

import com.xa.mass.eventbus.model.Task;

public class TaskAssignedEvent {
    private final Task task;
    public TaskAssignedEvent(Task task) { this.task = task; }
    public Task getTask() { return task; }
} 