package com.xa.mass.engine.runtime;

import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskRuntimeRetryPolicyResolverTest {

    @Test
    void interactiveTaskUsesShorterAssignmentRetryAndInteractiveWorkRetryDelay() {
        Task task = new Task();
        task.setWorkloadClass(TaskWorkloadClass.INTERACTIVE);

        TaskRuntimeRetryPolicyResolver resolver = new TaskRuntimeRetryPolicyResolver(
                75L,
                120L,
                0L,
                new TaskRuntimeProfileResolver()
        );

        TaskRuntimeRetryPolicy policy = resolver.resolve(task, 500L);

        assertEquals(TaskWorkloadClass.INTERACTIVE, policy.workloadClass());
        assertEquals(75L, policy.assignmentRetryDelayMillis());
        assertEquals(120L, policy.workRetryDelayMillis());
    }

    @Test
    void interactiveTaskDoesNotIncreaseAlreadySmallerAssignmentRetryDelay() {
        Task task = new Task();
        task.setWorkloadClass(TaskWorkloadClass.INTERACTIVE);

        TaskRuntimeRetryPolicyResolver resolver = new TaskRuntimeRetryPolicyResolver(
                100L,
                90L,
                0L,
                new TaskRuntimeProfileResolver()
        );

        TaskRuntimeRetryPolicy policy = resolver.resolve(task, 25L);

        assertEquals(25L, policy.assignmentRetryDelayMillis());
        assertEquals(90L, policy.workRetryDelayMillis());
    }

    @Test
    void bulkTaskKeepsDefaultAssignmentRetryAndBulkWorkRetryDelay() {
        Task task = new Task();
        task.setWorkloadClass(TaskWorkloadClass.BULK);

        TaskRuntimeRetryPolicyResolver resolver = new TaskRuntimeRetryPolicyResolver(
                50L,
                100L,
                15L,
                new TaskRuntimeProfileResolver()
        );

        TaskRuntimeRetryPolicy policy = resolver.resolve(task, 500L);

        assertEquals(TaskWorkloadClass.BULK, policy.workloadClass());
        assertEquals(500L, policy.assignmentRetryDelayMillis());
        assertEquals(15L, policy.workRetryDelayMillis());
    }
}
