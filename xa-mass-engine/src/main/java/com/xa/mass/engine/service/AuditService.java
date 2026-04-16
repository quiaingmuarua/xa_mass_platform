package com.xa.mass.engine.service;

import com.xa.mass.base.channel.eventbus.core.EventBusFacade;
import com.xa.mass.base.channel.eventbus.core.EventBusFactory;
import com.xa.mass.base.channel.eventbus.event.task.TaskAuditedEvent;
import com.xa.mass.base.channel.eventbus.event.task.TaskCreatedEvent;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal review bridge kept for legacy event-bus flows that still publish
 * {@link TaskCreatedEvent}. Active mainline task review goes through TaskManager.
 */
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    public void onTaskCreated(TaskCreatedEvent event) {
        Task task = event.getTask();
        boolean transitioned = task.transitionTo(TaskStatus.READY);
        if (!transitioned) {
            log.warn("[AuditService] Review transition rejected: tid={}, currentStatus={}",
                    task.getTid(), task.getStatus());
            return;
        }

        log.info("[AuditService] Task review approved: tid={}", task.getTid());
        EventBusFacade eventBus = EventBusFactory.get("guava");
        eventBus.post(new TaskAuditedEvent(task, null, null));
    }
}
