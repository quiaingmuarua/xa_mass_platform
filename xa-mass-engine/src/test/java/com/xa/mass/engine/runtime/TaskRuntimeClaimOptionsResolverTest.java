package com.xa.mass.engine.runtime;

import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import com.xa.mass.runtime.api.TaskWorkClaimOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskRuntimeClaimOptionsResolverTest {

    private final TaskRuntimeClaimOptionsResolver resolver = new TaskRuntimeClaimOptionsResolver();

    @Test
    void interactiveTasksResolveSmallClaimWindowAndShortLease() {
        Task task = new Task();
        task.getExecutionSpec().setBatchSize(8);
        task.getExecutionSpec().setWorkloadClass(TaskWorkloadClass.INTERACTIVE);

        TaskWorkClaimOptions options = resolver.resolve(task, 3, 300L);

        assertEquals(1, options.perWorkerCapacity());
        assertEquals(3, options.maxItems());
        assertEquals(30L, options.leaseSeconds());
    }

    @Test
    void interactiveLeaseDoesNotExceedSmallerGlobalLease() {
        Task task = new Task();
        task.getExecutionSpec().setBatchSize(8);
        task.getExecutionSpec().setWorkloadClass(TaskWorkloadClass.INTERACTIVE);

        TaskWorkClaimOptions options = resolver.resolve(task, 2, 5L);

        assertEquals(1, options.perWorkerCapacity());
        assertEquals(2, options.maxItems());
        assertEquals(5L, options.leaseSeconds());
    }

    @Test
    void bulkTasksKeepTaskBatchSizeAndNormalLease() {
        Task task = new Task();
        task.getExecutionSpec().setBatchSize(4);
        task.getExecutionSpec().setWorkloadClass(TaskWorkloadClass.BULK);

        TaskWorkClaimOptions options = resolver.resolve(task, 3, 120L);

        assertEquals(4, options.perWorkerCapacity());
        assertEquals(12, options.maxItems());
        assertEquals(120L, options.leaseSeconds());
    }
}

