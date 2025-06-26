package com.xa.mass.engine.service;

import com.google.common.eventbus.Subscribe;
import com.xa.mass.eventbus.model.Task;
import com.xa.mass.eventbus.enums.task.TaskStatus;
import com.xa.mass.eventbus.event.EventBusManager;
import com.xa.mass.eventbus.event.task.TaskCreatedEvent;
import com.xa.mass.eventbus.event.task.TaskAuditedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    @Subscribe
    public void onTaskCreated(TaskCreatedEvent event) {
        Task task = event.getTask();
        // 审核逻辑
        task.transitionTo(TaskStatus.READY);
        log.info("[AuditService] 审核通过: {}", task.getTid());
        // 审核通过后发布下一个事件
        EventBusManager.post(new TaskAuditedEvent(task));
    }
} 