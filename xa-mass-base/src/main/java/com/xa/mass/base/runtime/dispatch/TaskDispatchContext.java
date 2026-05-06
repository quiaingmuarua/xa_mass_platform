package com.xa.mass.base.runtime.dispatch;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable task-level dispatch snapshot carried across the engine -> transport
 * handoff seam.
 *
 * <p>This avoids relying on a live mutable {@link Task} reference once
 * assignment has already produced concrete dispatch bindings.</p>
 */
public record TaskDispatchContext(String taskId,
                                  String taskName,
                                  String project,
                                  String userId,
                                  String eventCode,
                                  Map<String, Object> sharedConfig) {

    public TaskDispatchContext {
        Objects.requireNonNull(taskId, "taskId");
        sharedConfig = immutableCopy(sharedConfig);
    }

    public static TaskDispatchContext from(Task task) {
        Objects.requireNonNull(task, "task");
        return new TaskDispatchContext(
                task.getTid(),
                task.getTaskName(),
                task.getProject(),
                task.getUser() != null ? task.getUser().getUserId() : null,
                TaskSharedConfig.sdkEventCode(task),
                task.getSharedConfig()
        );
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
