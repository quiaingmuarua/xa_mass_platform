package com.xa.mass.engine.service;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.eventbus.core.EventBusFacade;
import com.xa.mass.base.eventbus.core.EventBusFactory;
import com.xa.mass.base.eventbus.event.task.TaskAuditedEvent;
import com.xa.mass.base.eventbus.event.task.TaskCreatedEvent;
import com.xa.mass.base.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    public void onTaskCreated(TaskCreatedEvent event) {
        Task task = event.getTask();
        // 审核逻辑
        task.transitionTo(TaskStatus.READY);
        log.info("[AuditService] 审核通过: {}", task.getTid());
        // 审核通过后发布下一个事件
        EventBusFacade eventBus = EventBusFactory.get("guava");
        eventBus.post(new TaskAuditedEvent(task, null, null));
    }
} 