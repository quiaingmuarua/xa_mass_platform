package com.xa.mass.engine.runtime;

import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import com.xa.mass.runtime.api.WorkEnqueueOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskRuntimeEnqueueOptionsResolverTest {

    @Test
    void interactiveTasksResolveDedicatedReadyBacklogCap() {
        TaskRuntimeEnqueueOptionsResolver resolver = new TaskRuntimeEnqueueOptionsResolver(
                32,
                WorkEnqueueOptions.UNLIMITED,
                new TaskRuntimeProfileResolver()
        );
        Task task = new Task();
        task.setWorkloadClass(TaskWorkloadClass.INTERACTIVE);

        WorkEnqueueOptions options = resolver.resolve(task);

        assertEquals(32, options.maxReadyItemsPerTask());
    }

    @Test
    void bulkTasksKeepBulkBacklogCap() {
        TaskRuntimeEnqueueOptionsResolver resolver = new TaskRuntimeEnqueueOptionsResolver(
                32,
                4_096,
                new TaskRuntimeProfileResolver()
        );
        Task task = new Task();
        task.setWorkloadClass(TaskWorkloadClass.BULK);

        WorkEnqueueOptions options = resolver.resolve(task);

        assertEquals(4_096, options.maxReadyItemsPerTask());
    }
}

