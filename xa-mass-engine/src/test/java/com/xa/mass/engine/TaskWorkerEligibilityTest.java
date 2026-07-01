package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.task.runtime.ActiveLeaseRepairCandidate;
import com.xa.mass.task.runtime.TaskRuntimeProgressSnapshot;
import com.xa.mass.worker.runtime.control.WorkerDispatchBlockSignal;
import com.xa.mass.worker.runtime.control.WorkerDispatchBlockSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.xa.mass.runtime.worker.DispatchAvailabilitySource.TRANSPORT_DISCONNECTED;
import static com.xa.mass.runtime.worker.DispatchAvailabilitySource.WORKER_STATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskWorkerEligibilityTest {

    @Test
    void workerPrefilterExcludesBlockedLockedOccupiedAndRoutingMismatchCandidates() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-disconnected", "us");
        harness.addWorker("worker-locked", "us");
        harness.addWorker("worker-occupied", "us");
        harness.addWorker("worker-routing-mismatch", "gb");
        harness.addWorker("worker-eligible", "us");
        assertTrue(harness.workerManager.blockWorkerDispatch("pool-main", "worker-disconnected",
                disconnectedSignal("session disconnected", 1_000L)));
        assertTrue(harness.workerManager.tryAcquireWorkerExclusiveLease("worker-locked"));
        assertTrue(harness.workerManager.tryAcquireWorkerExclusiveLease("worker-occupied"));

        Task task = harness.createReadyBatchTask("eligibility", List.of(harness.item("eligible")));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));

        List<ActiveLeaseRepairCandidate> activeLeases = harness.activeLeases(task.getTid());
        assertEquals(1, activeLeases.size());
        assertEquals("worker-eligible", activeLeases.getFirst().workerId());
        assertEquals(1, harness.successfulMessageAssignments(task.getTid(), "worker-eligible"));

        assertTrue(harness.workerRecords(task.getTid(), "worker-disconnected").isEmpty());
        assertEquals(2, harness.selectionReasonCount(task.getTid(), "worker locked"));
        assertEquals(1, harness.selectionReasonCount(task.getTid(), "routing code mismatch"));
    }

    @Test
    void activeContentionExcludesWorkerAfterDisconnectBlockAndUsesBackupWorker() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-primary", "us");
        harness.addWorker("worker-backup", "us");
        Task firstTask = harness.createBatchTask(
                "dispatch-block-first",
                List.of(harness.item("first")),
                0,
                1,
                Map.of(
                        TaskSharedConfig.ROUTING_CODE, "us",
                        TaskSharedConfig.TARGET_WORKER_ID, "worker-primary"
                ),
                1
        );
        Task secondTask = harness.createReadyBatchTask("dispatch-block-second", List.of(harness.item("second")));
        assertTrue(harness.taskManager.approveTask(firstTask.getTid()));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(firstTask.getTid())));
        ActiveLeaseRepairCandidate firstLease = harness.activeLeases(firstTask.getTid()).getFirst();
        assertEquals("worker-primary", firstLease.workerId());

        assertTrue(harness.workerManager.blockWorkerDispatch("pool-main", "worker-primary",
                disconnectedSignal("current session disconnected", 2_000L)));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(secondTask.getTid())));

        List<ActiveLeaseRepairCandidate> secondLeases = harness.activeLeases(secondTask.getTid());
        assertEquals(1, secondLeases.size());
        assertEquals("worker-backup", secondLeases.getFirst().workerId());
        assertEquals(1, harness.activeLeases(firstTask.getTid()).size());
        assertTrue(harness.workerRecords(secondTask.getTid(), "worker-primary").isEmpty());
    }

    @Test
    void drainingWorkerIsExcludedFromNewAssignmentsUntilAvailableAgain() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-draining", "us");
        harness.addWorker("worker-backup", "us");
        Task firstTask = harness.createReadyBatchTask("draining-first", List.of(harness.item("first")));

        harness.workerManager.disableWorkerDispatch("worker-draining", WORKER_STATE, "maintenance");

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(firstTask.getTid())));

        List<ActiveLeaseRepairCandidate> firstLeases = harness.activeLeases(firstTask.getTid());
        assertEquals(1, firstLeases.size());
        assertEquals("worker-backup", firstLeases.getFirst().workerId());
        assertTrue(harness.workerRecords(firstTask.getTid(), "worker-draining").isEmpty());

        harness.workerManager.clearWorkerDispatchDisable("worker-draining", WORKER_STATE, "ready");

        Task secondTask = harness.createReadyBatchTask("draining-second", List.of(harness.item("second")));
        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(secondTask.getTid())));
        List<ActiveLeaseRepairCandidate> secondLeases = harness.activeLeases(secondTask.getTid());
        assertEquals(1, secondLeases.size());
        assertEquals("worker-draining", secondLeases.getFirst().workerId());
    }

    @Test
    void minimumWorkerGateUsesWorkerRuntimeEligibilityAndDoesNotHalfDispatchWhenWorkerDrops() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-stable", "us");
        harness.addWorker("worker-dropped", "us");
        Task task = harness.createBatchTask(
                "minimum-worker-dispatch-block",
                List.of(harness.item("alpha"), harness.item("beta")),
                0,
                1,
                Map.of(TaskSharedConfig.ROUTING_CODE, "us"),
                2
        );
        assertTrue(harness.taskManager.approveTask(task.getTid()));

        assertTrue(harness.workerManager.blockWorkerDispatch("pool-main", "worker-dropped",
                disconnectedSignal("current session disconnected", 3_000L)));

        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));

        Task updatedTask = harness.taskManager.getTask(task.getTid());
        TaskRuntimeProgressSnapshot stats = harness.stats(task.getTid());
        assertEquals(TaskStatus.READY, updatedTask.getStatus());
        assertEquals(2, stats.readyCount());
        assertEquals(0, stats.activeCount());
        assertTrue(harness.activeLeases(task.getTid()).isEmpty());
        assertFalse(harness.workerManager.hasWorkerExclusiveLease("worker-stable"));
        assertFalse(harness.workerManager.hasWorkerExclusiveLease("worker-dropped"));
        assertTrue(harness.workerRecords(task.getTid(), "worker-dropped").isEmpty());

        assertTrue(harness.workerManager.clearWorkerDispatchDisable(
                "worker-dropped",
                TRANSPORT_DISCONNECTED,
                "worker-runtime recheck passed"
        ));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));

        List<ActiveLeaseRepairCandidate> activeLeases = harness.activeLeases(task.getTid());
        assertEquals(2, activeLeases.size());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(task.getTid()).getStatus());
        assertEquals(0, harness.stats(task.getTid()).readyCount());
        assertEquals(2, harness.stats(task.getTid()).activeCount());
    }

    private static WorkerDispatchBlockSignal disconnectedSignal(String reason, long observedAtMillis) {
        return new WorkerDispatchBlockSignal(
                WorkerDispatchBlockSource.TRANSPORT_DISCONNECTED,
                reason,
                observedAtMillis,
                0L
        );
    }

}
