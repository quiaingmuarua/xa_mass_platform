package com.xa.mass.engine.service;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.channel.eventbus.core.EventBusFacade;
import com.xa.mass.base.channel.eventbus.core.EventBusFactory;
import com.xa.mass.base.channel.eventbus.event.task.TaskAuditedEvent;
import com.xa.mass.base.channel.eventbus.event.task.TaskCreatedEvent;
import com.xa.mass.base.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    public void onTaskCreated(TaskCreatedEvent event) {
        Task task = event.getTask();
        boolean transitioned = task.transitionTo(TaskStatus.READY);
        if (!transitioned) {
            // Task may already be in a non-NEW state (e.g. BLOCKED); do not fire
            // TaskAuditedEvent with a task that failed to reach READY.
            log.warn("[AuditService] 审核失败，状态不允许转换: tid={}, currentStatus={}", task.getTid(), task.getStatus());
            return;
        }
        log.info("[AuditService] 审核通过: {}", task.getTid());
        EventBusFacade eventBus = EventBusFactory.get("guava");
        eventBus.post(new TaskAuditedEvent(task, null, null));
    }
}
