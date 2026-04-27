package com.xa.mass.engine.runtime;

import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskRuntimeProfileResolverTest {

    private final TaskRuntimeProfileResolver resolver = new TaskRuntimeProfileResolver();

    @Test
    void resolvesInteractivePolicyProfile() {
        Task task = new Task();
        task.setWorkloadClass(TaskWorkloadClass.INTERACTIVE);

        TaskRuntimeProfile profile = resolver.resolve(task);

        assertEquals(TaskWorkloadClass.INTERACTIVE, profile.workloadClass());
        assertEquals(TaskRuntimeProfile.DispatchLane.INTERACTIVE, profile.dispatchLane());
        assertEquals(TaskRuntimeProfile.DispatchPriority.HIGH, profile.dispatchPriority());
        assertEquals(TaskRuntimeProfile.BatchPolicy.SMALL, profile.batchPolicy());
        assertEquals(TaskRuntimeProfile.LeaseProfile.SHORT, profile.leaseProfile());
        assertEquals(TaskRuntimeProfile.BackpressureClass.INTERACTIVE, profile.backpressureClass());
    }

    @Test
    void resolvesBulkPolicyProfileByDefault() {
        TaskRuntimeProfile profile = resolver.resolve(new Task());

        assertEquals(TaskWorkloadClass.BULK, profile.workloadClass());
        assertEquals(TaskRuntimeProfile.DispatchLane.BULK, profile.dispatchLane());
        assertEquals(TaskRuntimeProfile.DispatchPriority.NORMAL, profile.dispatchPriority());
        assertEquals(TaskRuntimeProfile.BatchPolicy.LARGE, profile.batchPolicy());
        assertEquals(TaskRuntimeProfile.LeaseProfile.NORMAL, profile.leaseProfile());
        assertEquals(TaskRuntimeProfile.BackpressureClass.BULK, profile.backpressureClass());
    }

    @Test
    void nullTaskAlsoFallsBackToBulkProfile() {
        TaskRuntimeProfile profile = resolver.resolve(null);

        assertEquals(TaskWorkloadClass.BULK, profile.workloadClass());
        assertEquals(TaskRuntimeProfile.DispatchLane.BULK, profile.dispatchLane());
    }
}
