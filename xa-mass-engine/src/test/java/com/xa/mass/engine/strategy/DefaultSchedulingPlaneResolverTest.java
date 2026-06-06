package com.xa.mass.engine.strategy;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.engine.runtime.TaskRuntimeProfile;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy.DispatchCadence;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy.WorkerResourceMode;
import com.xa.mass.engine.runtime.scheduling.ResolvedWorkerSchedulingPolicy;
import com.xa.mass.engine.runtime.scheduling.SchedulingPlaneResolution;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DefaultSchedulingPlaneResolverTest {

    private final DefaultSchedulingPlaneResolver resolver = new DefaultSchedulingPlaneResolver();

    @Test
    void resolvesTaskDispatchIntentAndTaskSchedulingProfileWithoutBehaviorChange() {
        Task task = task();
        task.getExecutionSpec().setWorkloadClass(TaskWorkloadClass.INTERACTIVE);
        task.getExecutionSpec().setBatchSize(3);
        task.getExecutionSpec().setDefaultMaxRetryCount(2);
        task.setMinRequiredWorkerCount(4);

        SchedulingPlaneResolution resolution = resolver.resolve(task);
        ResolvedTaskSchedulingPolicy taskPolicy = resolution.taskSchedulingPolicy();

        assertEquals("task-psp", resolution.dispatchIntent().taskId());
        assertEquals("demoApp", resolution.dispatchIntent().project());
        assertEquals(List.of("pool-a", "pool-b"), resolution.dispatchIntent().workerGroupIds());
        assertEquals("node-a", resolution.dispatchIntent().adapterNodeId());
        assertEquals("worker-target", resolution.dispatchIntent().targetWorkerId());
        assertEquals("us", resolution.dispatchIntent().routingCode());
        assertEquals(Map.of("region", "us"), resolution.dispatchIntent().routeAttributes());
        assertEquals(Map.of("fingerprintProfile", "fp-alpha"), resolution.dispatchIntent().targetWorkerAttributes());

        assertEquals(TaskWorkloadClass.INTERACTIVE, taskPolicy.workloadClass());
        assertEquals(TaskRuntimeProfile.DispatchLane.INTERACTIVE, taskPolicy.dispatchLane());
        assertEquals(TaskRuntimeProfile.DispatchPriority.HIGH, taskPolicy.dispatchPriority());
        assertEquals(TaskRuntimeProfile.BatchPolicy.SMALL, taskPolicy.batchPolicy());
        assertEquals(TaskRuntimeProfile.LeaseProfile.SHORT, taskPolicy.leaseProfile());
        assertEquals(TaskRuntimeProfile.BackpressureClass.INTERACTIVE, taskPolicy.backpressureClass());
        assertEquals("BATCH", taskPolicy.taskPolicyPreset());
        assertEquals(DispatchCadence.RUNTIME_READY_POLLING, taskPolicy.dispatchCadence());
        assertEquals(WorkerResourceMode.EXCLUSIVE, taskPolicy.workerResourceMode());
        assertEquals(true, taskPolicy.idleClosePolicy().enabled());
        assertEquals(true, taskPolicy.idleClosePolicy().requireIntakeSealed());
        assertEquals(TaskRuntimeProfile.BatchPolicy.SMALL, taskPolicy.claimPolicy().batchPolicy());
        assertEquals(TaskRuntimeProfile.LeaseProfile.SHORT, taskPolicy.claimPolicy().leaseProfile());
        assertEquals(1, taskPolicy.claimPolicy().smallPerWorkerCapacityLimit());
        assertEquals(30L, taskPolicy.claimPolicy().shortLeaseSeconds());
        assertEquals(TaskWorkloadClass.INTERACTIVE, taskPolicy.retryPolicy().workloadClass());
        assertEquals(100L, taskPolicy.retryPolicy().interactiveAssignmentRetryDelayMillis());
        assertEquals(100L, taskPolicy.retryPolicy().interactiveWorkRetryDelayMillis());
        assertEquals(0L, taskPolicy.retryPolicy().bulkWorkRetryDelayMillis());
        assertEquals(true, taskPolicy.resultFinalityPolicy().retryExpiredLeaseFromAnyActiveState());
        assertEquals(true, taskPolicy.resultFinalityPolicy().expiredLeaseFinalizesAsFailure());
        assertEquals(TaskRuntimeProfile.BackpressureClass.INTERACTIVE,
                taskPolicy.backpressurePolicy().backpressureClass());
        assertEquals(10_000, taskPolicy.backpressurePolicy().maxReadyItemsPerTask());
        assertEquals(3, taskPolicy.batchSize());
        assertEquals(2, taskPolicy.defaultMaxRetryCount());
        assertEquals(4, taskPolicy.minRequiredWorkerCount());
    }

    @Test
    void resolvesSessionPresetAsSignalDrivenAndNonExclusiveWhenForegroundIsFalse() {
        Task task = task();
        task.setContract(TaskContract.SESSION);
        task.getExecutionSpec().setWorkloadClass(TaskWorkloadClass.INTERACTIVE);
        task.getExecutionSpec().setForeground(false);

        ResolvedTaskSchedulingPolicy taskPolicy = resolver.resolve(task).taskSchedulingPolicy();

        assertEquals("SESSION", taskPolicy.taskPolicyPreset());
        assertEquals(DispatchCadence.SIGNAL_DRIVEN_DELAYED, taskPolicy.dispatchCadence());
        assertEquals(WorkerResourceMode.CAPACITY, taskPolicy.workerResourceMode());
        assertEquals(false, taskPolicy.idleClosePolicy().enabled());
        assertEquals(false, taskPolicy.idleClosePolicy().requireIntakeSealed());
        assertEquals(TaskRuntimeProfile.BatchPolicy.SMALL, taskPolicy.claimPolicy().batchPolicy());
        assertEquals(TaskRuntimeProfile.LeaseProfile.SHORT, taskPolicy.claimPolicy().leaseProfile());
        assertEquals(1, taskPolicy.claimPolicy().smallPerWorkerCapacityLimit());
        assertEquals(30L, taskPolicy.claimPolicy().shortLeaseSeconds());
        assertEquals(TaskWorkloadClass.INTERACTIVE, taskPolicy.retryPolicy().workloadClass());
        assertEquals(100L, taskPolicy.retryPolicy().interactiveAssignmentRetryDelayMillis());
        assertEquals(100L, taskPolicy.retryPolicy().interactiveWorkRetryDelayMillis());
        assertEquals(0L, taskPolicy.retryPolicy().bulkWorkRetryDelayMillis());
        assertEquals(false, taskPolicy.resultFinalityPolicy().retryExpiredLeaseFromAnyActiveState());
        assertEquals(false, taskPolicy.resultFinalityPolicy().expiredLeaseFinalizesAsFailure());
        assertEquals(TaskRuntimeProfile.BackpressureClass.INTERACTIVE,
                taskPolicy.backpressurePolicy().backpressureClass());
        assertEquals(10_000, taskPolicy.backpressurePolicy().maxReadyItemsPerTask());
    }

    @Test
    void resolvesWorkerSchedulingPolicyFromCurrentRouteBucketRules() {
        SchedulingPlaneResolution resolution = resolver.resolve(task());
        ResolvedWorkerSchedulingPolicy workerPolicy = resolution.workerSchedulingPolicy();

        assertEquals("task-psp", workerPolicy.taskId());
        assertEquals("demoApp", workerPolicy.project());
        assertEquals("event.probe", workerPolicy.eventCode());
        assertEquals(List.of("pool-a", "pool-b"), workerPolicy.workerGroupIds());
        assertEquals("node-a", workerPolicy.adapterNodeId());
        assertEquals("us", workerPolicy.routingCode());
        assertEquals(Map.of("region", "us"), workerPolicy.routeAttributes());
        assertEquals("worker-target", workerPolicy.targetWorkerId());
        assertEquals(Map.of("fingerprintProfile", "fp-alpha"), workerPolicy.targetWorkerAttributes());
        assertTrue(workerPolicy.routeBucketKeys().stream().anyMatch(key -> key.contains("region")));
    }

    private static Task task() {
        Task task = new Task();
        task.setTid("task-psp");
        task.setProject("demoApp");
        task.setSharedConfig(Map.of(
                TaskSharedConfig.WORKER_GROUP_IDS, List.of("pool-a", "pool-b"),
                TaskSharedConfig.ADAPTER_NODE_ID, "node-a",
                TaskSharedConfig.TARGET_WORKER_ID, "worker-target",
                TaskSharedConfig.TARGET_WORKER_ATTRIBUTES, Map.of("fingerprintProfile", "fp-alpha"),
                TaskSharedConfig.ROUTING_CODE, "us",
                TaskSharedConfig.ROUTE_ATTRIBUTES, Map.of("region", "us"),
                TaskSharedConfig.SDK_METADATA, Map.of(TaskSharedConfig.SDK_EVENT_CODE, "event.probe")
        ));
        return task;
    }
}
