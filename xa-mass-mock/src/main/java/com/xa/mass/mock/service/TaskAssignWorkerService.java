package com.xa.mass.mock.service;

import com.google.common.eventbus.Subscribe;
import com.xa.mass.engine.listener.TaskAssignWorker;
import com.xa.mass.eventbus.model.Task;
import com.xa.mass.mock.event.TaskCreatedEvent;

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