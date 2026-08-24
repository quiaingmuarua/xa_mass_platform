package com.xa.mass.server.taskdata;

import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.server.api.v1.model.TaskItemRequest;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class TaskItemMapper {

    private final Clock clock;

    public TaskItemMapper() {
        this(Clock.systemUTC());
    }

    TaskItemMapper(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public long nowMillis() {
        return clock.millis();
    }

    public TaskItem finiteItem(
            TaskItemRequest request,
            long createdAtMillis
    ) {
        if (request.allocationRule() != null) {
            throw new IllegalArgumentException(
                    "finite TaskItem forbids allocationRule"
            );
        }
        return item(request, createdAtMillis, null);
    }

    public TaskItem directItem(
            TaskItemRequest request,
            long createdAtMillis
    ) {
        if (request.allocationRule() == null) {
            throw new IllegalArgumentException(
                    "WorkerGroup Task Call requires allocationRule"
            );
        }
        return item(
                request,
                createdAtMillis,
                request.allocationRule()
        );
    }

    private static TaskItem item(
            TaskItemRequest request,
            long createdAtMillis,
            java.util.Map<String, Object> allocationRule
    ) {
        Objects.requireNonNull(request, "request");
        Long expireAtMillis = null;
        if (request.ttlMillis() != null) {
            try {
                expireAtMillis = Math.addExact(
                        createdAtMillis,
                        request.ttlMillis()
                );
            } catch (ArithmeticException error) {
                throw new IllegalArgumentException(
                        "ttlMillis exceeds the supported time range",
                        error
                );
            }
        }
        return new TaskItem(
                request.messageId(),
                request.eventCode(),
                createdAtMillis,
                request.payload(),
                request.priority(),
                expireAtMillis,
                allocationRule
        );
    }
}
