package com.xa.mass.engine.runtime;

import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskRuntimeWorkRetryOptionsResolverTest {

    @Test
    void interactiveTaskUsesInteractiveRetryDelay() {
        Task task = new Task();
        task.setWorkloadClass(TaskWorkloadClass.INTERACTIVE);

        TaskRuntimeWorkRetryOptionsResolver resolver =
                new TaskRuntimeWorkRetryOptionsResolver(new TaskRuntimeProfileResolver());

        assertEquals(TaskRuntimeWorkRetryOptionsResolver.DEFAULT_INTERACTIVE_WORK_RETRY_DELAY_MILLIS,
                resolver.resolveRetryDelayMillis(task));
    }

    @Test
    void bulkTaskUsesBulkRetryDelay() {
        Task task = new Task();
        task.setWorkloadClass(TaskWorkloadClass.BULK);

        TaskRuntimeWorkRetryOptionsResolver resolver =
                new TaskRuntimeWorkRetryOptionsResolver(new TaskRuntimeProfileResolver());

        assertEquals(TaskRuntimeWorkRetryOptionsResolver.DEFAULT_BULK_WORK_RETRY_DELAY_MILLIS,
                resolver.resolveRetryDelayMillis(task));
    }
}
