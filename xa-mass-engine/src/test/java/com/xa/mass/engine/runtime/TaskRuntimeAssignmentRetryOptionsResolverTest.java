package com.xa.mass.engine.runtime;

import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskRuntimeAssignmentRetryOptionsResolverTest {

    @Test
    void interactiveTaskUsesShorterRetryDelay() {
        Task task = new Task();
        task.setWorkloadClass(TaskWorkloadClass.INTERACTIVE);

        TaskRuntimeAssignmentRetryOptionsResolver resolver =
                new TaskRuntimeAssignmentRetryOptionsResolver(75L, new TaskRuntimeProfileResolver());

        assertEquals(75L, resolver.resolve(task, 500L));
    }

    @Test
    void interactiveTaskDoesNotIncreaseAlreadySmallerDefaultRetryDelay() {
        Task task = new Task();
        task.setWorkloadClass(TaskWorkloadClass.INTERACTIVE);

        TaskRuntimeAssignmentRetryOptionsResolver resolver =
                new TaskRuntimeAssignmentRetryOptionsResolver(100L, new TaskRuntimeProfileResolver());

        assertEquals(25L, resolver.resolve(task, 25L));
    }

    @Test
    void bulkTaskKeepsDefaultRetryDelay() {
        Task task = new Task();
        task.setWorkloadClass(TaskWorkloadClass.BULK);

        TaskRuntimeAssignmentRetryOptionsResolver resolver =
                new TaskRuntimeAssignmentRetryOptionsResolver(50L, new TaskRuntimeProfileResolver());

        assertEquals(500L, resolver.resolve(task, 500L));
    }
}
