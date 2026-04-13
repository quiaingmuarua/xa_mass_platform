package com.xa.mass.base.old.eventbus.event.task;

import com.xa.mass.base.channel.eventbus.core.MassEvent;
import com.xa.mass.base.channel.eventbus.core.MassPlatformEventType;
import com.xa.mass.base.model.Task;

import java.util.Collections;
import java.util.Map;

public class TaskAssignedEvent extends MassEvent.BaseMassEvent {
    private final Task task;

    public TaskAssignedEvent(Task task, String traceId, String requestId) {
        super(
                "TASK_ASSIGNED",
                MassPlatformEventType.TASK_ASSIGNED,
                String.format("任务分配: %s", task != null ? task.getTid() : "null"),
                createMetadata(task),
                traceId,
                requestId
        );
        this.task = task;
    }

    private static Map<String, Object> createMetadata(Task task) {
        return task != null ? Collections.singletonMap("taskId", task.getTid()) : Collections.emptyMap();
    }

    public Task getTask() {
        return task;
    }
}