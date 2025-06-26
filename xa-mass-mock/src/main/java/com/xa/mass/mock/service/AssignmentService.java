package com.xa.mass.mock.service;

import com.google.common.eventbus.Subscribe;
import com.xa.mass.eventbus.model.Task;
import com.xa.mass.eventbus.event.EventBusManager;
import com.xa.mass.mock.event.TaskAuditedEvent;
import com.xa.mass.mock.event.TaskAssignedEvent;

public class AssignmentService {
    @Subscribe
    public void onTaskAudited(TaskAuditedEvent event) {
        Task task = event.getTask();
        // 分配逻辑
        System.out.println("[AssignmentService] 分配任务: " + task.getTid());
        // ...分配完成后
        EventBusManager.post(new TaskAssignedEvent(task));
    }
} 