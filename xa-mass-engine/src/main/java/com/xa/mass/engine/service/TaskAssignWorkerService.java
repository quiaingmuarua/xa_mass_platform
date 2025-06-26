package com.xa.mass.engine.service;

import com.google.common.eventbus.Subscribe;
import com.xa.mass.engine.listener.TaskAssignWorker;
import com.xa.mass.eventbus.event.task.TaskCreatedEvent;

public class TaskAssignWorkerService {
    private final TaskAssignWorker worker;
    public TaskAssignWorkerService(TaskAssignWorker worker) {
        this.worker = worker;
    }

    @Subscribe
    public void onTaskCreated(TaskCreatedEvent event) {
        worker.submit(event.getTask());
    }
} 