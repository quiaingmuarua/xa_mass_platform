package com.xa.mass.mock.event;

import com.xa.mass.eventbus.model.Task;

public class TaskCreatedEvent {
    private final Task task;
    public TaskCreatedEvent(Task task) { this.task = task; }
    public Task getTask() { return task; }
} 