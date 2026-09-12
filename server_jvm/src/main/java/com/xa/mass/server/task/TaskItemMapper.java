package com.xa.mass.server.task;

import org.springframework.stereotype.Component;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.server.api.v1.contract.task.TaskItemRequest;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import com.xa.mass.kernel.task.TaskItemWorkerSelector;

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
        return item(request, createdAtMillis, TaskItemWorkerSelector.parse(
                request.workerSelector() == null ? Map.of() : request.workerSelector()));
    }

    public TaskItem callItem(
            TaskItemRequest request,
            long createdAtMillis,
            TaskItemWorkerSelector selector
    ) {
        if (request.workerSelector() == null) {
            throw new IllegalArgumentException(
                    "WorkerGroup Task Call requires workerSelector"
            );
        }
        return item(request, createdAtMillis, selector);
    }

    private static TaskItem item(
            TaskItemRequest request,
            long createdAtMillis,
            TaskItemWorkerSelector selector
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
                selector
        );
    }
}
