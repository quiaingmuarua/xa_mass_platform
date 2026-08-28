package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.pacer.dispatch.TaskSchedulingMechanism.TaskSchedulingObservation;
import java.util.List;
import java.util.Objects;

final class TaskInitializationPolicy {

    private final TaskSchedulingMechanism mechanism;

    TaskInitializationPolicy(TaskSchedulingMechanism mechanism) {
        this.mechanism = Objects.requireNonNull(mechanism, "mechanism");
    }

    int initializeTasks(List<DueTaskObservation> tasks) {
        Objects.requireNonNull(tasks, "tasks");
        if (tasks.isEmpty()) {
            return 0;
        }
        List<TaskSchedulingObservation> observations = tasks.stream()
                .map(TaskInitializationPolicy::schedulingObservation)
                .toList();
        return mechanism.onInitializationReady(
                mechanism.observeInitializationReady(observations)
        );
    }

    private static TaskSchedulingObservation schedulingObservation(
            DueTaskObservation task
    ) {
        return new TaskSchedulingObservation(
                task.taskId(),
                task.descriptor(),
                task.reference()
        );
    }
}
