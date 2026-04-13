package com.xa.mass.engine.service;

import com.xa.mass.base.channel.eventbus.core.EventBusFacade;
import com.xa.mass.base.channel.eventbus.core.EventBusFactory;
import com.xa.mass.base.channel.eventbus.event.task.TaskAssignedEvent;
import com.xa.mass.base.channel.eventbus.event.task.TaskAuditedEvent;
import com.xa.mass.base.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AssignmentService {
    private static final Logger log = LoggerFactory.getLogger(AssignmentService.class);

    public void onTaskAudited(TaskAuditedEvent event) {
        Task task = event.getTask();
        // 鍒嗛厤閫昏緫
        log.info("[AssignmentService] 鍒嗛厤浠诲姟: {}", task.getTid());
        // ...鍒嗛厤瀹屾垚鍚?
        EventBusFacade eventBus = EventBusFactory.get("guava");
        eventBus.post(new TaskAssignedEvent(task, null, null));
    }
} 
