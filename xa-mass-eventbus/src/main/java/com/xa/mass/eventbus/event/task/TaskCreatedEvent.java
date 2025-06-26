package com.xa.mass.eventbus.event.task;

import com.xa.mass.eventbus.model.Task;

public class TaskCreatedEvent {
    private final Task task;
    public TaskCreatedEvent(Task task) { this.task = task; }
    public Task getTask() { return task; }
} 