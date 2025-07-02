package com.xa.mass.engine.service;

import com.xa.mass.base.eventbus.task.TaskAuditedEvent;
import com.xa.mass.engine.listener.TaskAssignWorker;

public class TaskAssignWorkerService {
    private final TaskAssignWorker worker;
    public TaskAssignWorkerService(TaskAssignWorker worker) {
        this.worker = worker;
    }

    public void onTaskAudited(TaskAuditedEvent event) {
        worker.submit(event.getTask());
    }
} 