package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import java.util.List;
import java.util.Objects;

interface TaskSchedulingMechanism {

    NormalTaskObservationPage observeNormalTasks(int limit);

    List<TaskSchedulingObservation> observeInitialTasks(int limit);

    List<TaskSchedulingObservation> observeInitializationReady(
            List<TaskSchedulingObservation> tasks
    );

    int onInitializationReady(List<TaskSchedulingObservation> tasks);

    record TaskSchedulingObservation(
            String taskId,
            TaskDescriptor descriptor,
            TaskSchedulingReference reference
    ) {
        public TaskSchedulingObservation {
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalArgumentException(
                        "taskId must be non-blank"
                );
            }
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(reference, "reference");
            if (!taskId.equals(descriptor.taskId())) {
                throw new IllegalArgumentException(
                        "Task observation descriptor identity mismatch"
                );
            }
        }
    }

    record NormalTaskObservationPage(
            int sourceCount,
            long readAtMillis,
            List<TaskSchedulingObservation> tasks
    ) {
        public NormalTaskObservationPage {
            if (sourceCount < 0 || readAtMillis < 0) {
                throw new IllegalArgumentException(
                        "Task observation page metadata must be non-negative"
                );
            }
            tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks"));
            if (tasks.size() > sourceCount) {
                throw new IllegalArgumentException(
                        "Validated tasks cannot exceed source count"
                );
            }
        }
    }
}
