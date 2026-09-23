package com.xa.mass.server.task;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class TaskIdGenerator {

    public String nextTaskId() {
        return "task-" + UUID.randomUUID();
    }
}
