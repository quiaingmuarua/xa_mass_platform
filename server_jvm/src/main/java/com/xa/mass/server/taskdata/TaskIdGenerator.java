package com.xa.mass.server.taskdata;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class TaskIdGenerator {

    public String nextTaskId() {
        return "task-" + UUID.randomUUID();
    }
}
