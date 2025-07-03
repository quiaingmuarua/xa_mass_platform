package com.xa.mass.base.eventbus.task;

import com.xa.mass.base.eventbus.core.MassEvent;
import com.xa.mass.base.eventbus.core.MassPlatformEventType;
import com.xa.mass.base.model.Task;

import java.util.Collections;
import java.util.Map;

public class TaskCreatedEvent extends MassEvent.BaseMassEvent {
    private final Task task;

    public TaskCreatedEvent(Task task, String traceId, String requestId) {
        super(
                "TASK_CREATED",
                MassPlatformEventType.TASK_CREATED,
                String.format("任务创建: %s", task != null ? task.getTid() : "null"),
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