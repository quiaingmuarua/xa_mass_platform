package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import java.util.Objects;

/** A due Task descriptor paired with its exact observed scheduling score. */
record ObservedTask(
        TaskDescriptor descriptor,
        long observedTaskScore
) {

    ObservedTask {
        Objects.requireNonNull(descriptor, "descriptor");
    }

    String taskId() {
        return descriptor.taskId();
    }
}
