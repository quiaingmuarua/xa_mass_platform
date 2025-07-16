package com.xa.mass.engine.service;

import com.xa.mass.base.channel.eventbus.core.EventBusFacade;
import com.xa.mass.base.old.eventbus.core.EventBusFactory;
import com.xa.mass.base.old.eventbus.event.task.TaskAssignedEvent;
import com.xa.mass.base.old.eventbus.event.task.TaskAuditedEvent;
import com.xa.mass.base.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AssignmentService {
    private static final Logger log = LoggerFactory.getLogger(AssignmentService.class);

    public void onTaskAudited(TaskAuditedEvent event) {
        Task task = event.getTask();
        // 分配逻辑
        log.info("[AssignmentService] 分配任务: {}", task.getTid());
        // ...分配完成后
        EventBusFacade eventBus = EventBusFactory.get("guava");
        eventBus.post(new TaskAssignedEvent(task, null, null));
    }
} 