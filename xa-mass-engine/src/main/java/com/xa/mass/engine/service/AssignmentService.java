package com.xa.mass.engine.service;

import com.google.common.eventbus.Subscribe;
import com.xa.mass.eventbus.model.Task;
import com.xa.mass.eventbus.event.EventBusManager;
import com.xa.mass.eventbus.event.task.TaskAuditedEvent;
import com.xa.mass.eventbus.event.task.TaskAssignedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AssignmentService {
    private static final Logger log = LoggerFactory.getLogger(AssignmentService.class);

    @Subscribe
    public void onTaskAudited(TaskAuditedEvent event) {
        Task task = event.getTask();
        // 分配逻辑
        log.info("[AssignmentService] 分配任务: {}", task.getTid());
        // ...分配完成后
        EventBusManager.post(new TaskAssignedEvent(task));
    }
} 