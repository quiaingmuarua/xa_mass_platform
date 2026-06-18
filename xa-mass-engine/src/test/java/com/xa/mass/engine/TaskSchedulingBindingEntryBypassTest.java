package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.engine.listener.TaskAssignWorker;
import com.xa.mass.engine.watchdog.RuntimeReadyDispatchPump;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.TaskWorkStats;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskSchedulingBindingEntryBypassTest {

    @Test
    void runtimeReadyPumpHonorsWorkerGroupPolicyBeforeBinding() throws InterruptedException {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-selected", "pool-selected", "us", Map.of());
        harness.addWorker("worker-other", "pool-other", "us", Map.of());
        Task task = harness.createBatchTask(
                "pump-worker-group-policy",
                List.of(harness.item("selected-group")),
                0,
                1,
                Map.of(
                        TaskSharedConfig.ROUTING_CODE, "us",
                        TaskSharedConfig.WORKER_GROUP_ID, "pool-selected"
                ),
                1
        );
        assertTrue(harness.taskManager.approveTask(task.getTid()));

        RuntimeReadyDispatchPump pump = new RuntimeReadyDispatchPump(
                harness.taskManager,
                dispatchable -> harness.assignListener.onTaskAssign(
                        harness.taskManager.getTask(dispatchable.getTid())),
                50L,
                10,
                1_000L,
                1_000L
        );

        try {
            pump.start();
            awaitCondition(
                    () -> hasActiveLease(harness, task.getTid(), "worker-selected")
                            && harness.taskManager.getTask(task.getTid()).getStatus() == TaskStatus.RUNNING
                            && harness.stats(task.getTid()).readyCount() == 0
                            && harness.stats(task.getTid()).inflightCount() == 1
                            && harness.successfulMessageAssignments(task.getTid(), "worker-selected") == 1
                            && hasDispatchBinding(harness, task.getTid(), "worker-selected"),
                    "runtime-ready pump should bind only the selected worker group"
            );
        } finally {
            pump.stop();
        }

        assertSelectedLeaseAndCounters(harness, task.getTid(), "worker-selected", 0, 1);
        assertNoWorkerAttemptOrBinding(harness, task.getTid(), "worker-other");
    }

    @Test
    void workerAvailabilityWakeupRetryHonorsTargetWorkerPolicyBeforeBinding() throws InterruptedException {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-target", "us");
        harness.addWorker("worker-backup", "us");
        assertTrue(harness.workerManager.tryAcquireWorkerExclusiveLease("worker-target"));
        Task task = harness.createBatchTask(
                "wakeup-target-policy",
                List.of(harness.item("target-only")),
                0,
                1,
                Map.of(
                        TaskSharedConfig.ROUTING_CODE, "us",
                        TaskSharedConfig.TARGET_WORKER_ID, "worker-target"
                ),
                1
        );
        assertTrue(harness.taskManager.approveTask(task.getTid()));

        TaskAssignWorker assignWorker = new TaskAssignWorker(harness.assignListener, 5_000L);
        TaskDispatchWakeupBridge wakeupBridge = new TaskDispatchWakeupBridge(assignWorker, () -> {
        });
        try {
            assignWorker.start();
            assertEquals(
                    TaskAssignWorker.SubmitResult.ACCEPTED,
                    assignWorker.submitDetailed(harness.taskManager.getTask(task.getTid()))
            );

            awaitCondition(
                    () -> harness.selectionReasonCount(task.getTid(), "worker locked") > 0,
                    "first assignment attempt should record target worker conflict before wakeup"
            );
            assertEquals(TaskStatus.READY, harness.taskManager.getTask(task.getTid()).getStatus());
            assertSelectedCounters(harness, task.getTid(), 1, 0);
            assertNoWorkerAttemptOrBinding(harness, task.getTid(), "worker-backup");
            assertEquals(1, harness.selectionReasonCount(task.getTid(), "worker locked"));

            harness.workerManager.releaseWorkerExclusiveLease("worker-target");
            awaitCondition(
                    () -> {
                        wakeupBridge.wake("target worker available");
                        return hasActiveLease(harness, task.getTid(), "worker-target");
                    },
                    "worker-availability wakeup should retry through target-worker policy"
            );
        } finally {
            assignWorker.stop();
        }

        assertSelectedLeaseAndCounters(harness, task.getTid(), "worker-target", 0, 1);
        assertNoWorkerAttemptOrBinding(harness, task.getTid(), "worker-backup");
    }

    @Test
    void leaseExpiryRedispatchReappliesTargetPolicyAndDoesNotBindBackup() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-target", "us");
        harness.addWorker("worker-backup", "us");
        Task task = harness.createBatchTask(
                "lease-expiry-target-policy",
                List.of(harness.item("target-only")),
                1,
                1,
                Map.of(
                        TaskSharedConfig.ROUTING_CODE, "us",
                        TaskSharedConfig.TARGET_WORKER_ID, "worker-target"
                ),
                1
        );
        assertTrue(harness.taskManager.approveTask(task.getTid()));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));
        ActiveLeaseRecord firstLease = singleLease(harness, task.getTid());
        assertEquals("worker-target", firstLease.workerId());
        assertEquals(0, firstLease.retryCount());
        assertNoWorkerAttemptOrBinding(harness, task.getTid(), "worker-backup");

        assertTrue(harness.taskManager.expireLeasedWork(task.getTid(), firstLease.messageId()));
        assertSelectedCounters(harness, task.getTid(), 1, 0);
        assertTrue(harness.activeLeases(task.getTid()).isEmpty());
        assertFalse(harness.workerManager.hasWorkerExclusiveLease("worker-target"));

        assertTrue(harness.workerManager.tryAcquireWorkerExclusiveLease("worker-target"));
        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));
        assertSelectedCounters(harness, task.getTid(), 1, 0);
        assertTrue(harness.activeLeases(task.getTid()).isEmpty());
        assertNoWorkerAttemptOrBinding(harness, task.getTid(), "worker-backup");

        harness.workerManager.releaseWorkerExclusiveLease("worker-target");
        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));

        ActiveLeaseRecord redispatchedLease = singleLease(harness, task.getTid());
        assertEquals(firstLease.messageId(), redispatchedLease.messageId());
        assertEquals("worker-target", redispatchedLease.workerId());
        assertEquals(1, redispatchedLease.retryCount());
        assertSelectedCounters(harness, task.getTid(), 0, 1);
        assertNoWorkerAttemptOrBinding(harness, task.getTid(), "worker-backup");
    }

    private static void assertSelectedLeaseAndCounters(TaskSchedulingTestHarness harness,
                                                       String taskId,
                                                       String workerId,
                                                       int readyCount,
                                                       int inflightCount) {
        ActiveLeaseRecord lease = singleLease(harness, taskId);
        assertEquals(workerId, lease.workerId());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(taskId).getStatus());
        assertSelectedCounters(harness, taskId, readyCount, inflightCount);
        assertTrue(harness.workerManager.hasWorkerExclusiveLease(workerId));
        assertEquals(1, harness.successfulMessageAssignments(taskId, workerId));
        assertTrue(hasDispatchBinding(harness, taskId, workerId));
    }

    private static void assertSelectedCounters(TaskSchedulingTestHarness harness,
                                               String taskId,
                                               int readyCount,
                                               int inflightCount) {
        TaskWorkStats stats = harness.stats(taskId);
        assertEquals(readyCount, stats.readyCount());
        assertEquals(inflightCount, stats.inflightCount());
    }

    private static void assertNoWorkerAttemptOrBinding(TaskSchedulingTestHarness harness,
                                                       String taskId,
                                                       String workerId) {
        assertTrue(harness.workerRecords(taskId, workerId).isEmpty());
        assertEquals(0, harness.successfulMessageAssignments(taskId, workerId));
        assertFalse(hasDispatchBinding(harness, taskId, workerId));
        assertFalse(harness.workerManager.hasWorkerExclusiveLease(workerId));
    }

    private static ActiveLeaseRecord singleLease(TaskSchedulingTestHarness harness, String taskId) {
        List<ActiveLeaseRecord> activeLeases = harness.activeLeases(taskId);
        assertEquals(1, activeLeases.size());
        return activeLeases.getFirst();
    }

    private static boolean hasActiveLease(TaskSchedulingTestHarness harness,
                                          String taskId,
                                          String workerId) {
        return harness.activeLeases(taskId).stream()
                .anyMatch(lease -> workerId.equals(lease.workerId()));
    }

    private static boolean hasDispatchBinding(TaskSchedulingTestHarness harness,
                                              String taskId,
                                              String workerId) {
        return harness.dispatches.stream()
                .anyMatch(binding -> taskId.equals(binding.taskId())
                        && workerId.equals(binding.workerId()));
    }

    private static void awaitCondition(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10L);
        }
        assertTrue(condition.getAsBoolean(), message);
    }
}
