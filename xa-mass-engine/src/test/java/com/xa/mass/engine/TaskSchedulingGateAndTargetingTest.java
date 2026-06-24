package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.worker.runtime.control.WorkerDispatchBlockSignal;
import com.xa.mass.worker.runtime.control.WorkerDispatchBlockSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskSchedulingGateAndTargetingTest {

    @Test
    void blockedWorkerIsRejectedBeforeBindingWork() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-blocked", "us");
        assertTrue(harness.workerManager.blockWorkerDispatch("pool-main", "worker-blocked",
                new WorkerDispatchBlockSignal(
                        WorkerDispatchBlockSource.TRANSPORT_DISCONNECTED,
                        "current session disconnected",
                        1_000L,
                        0L
                )));
        Task task = harness.createBatchTask(
                "blocked-worker",
                List.of(harness.item("alpha")),
                0,
                1
        );
        assertTrue(harness.taskManager.approveTask(task.getTid()));

        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));

        assertEquals(TaskStatus.READY, harness.taskManager.getTask(task.getTid()).getStatus());
        assertEquals(1, harness.stats(task.getTid()).readyCount());
        assertEquals(0, harness.stats(task.getTid()).inflightCount());
        assertTrue(harness.activeLeases(task.getTid()).isEmpty());
        assertFalse(harness.workerManager.hasWorkerExclusiveLease("worker-blocked"));
        assertTrue(harness.workerRecords(task.getTid(), "worker-blocked").isEmpty());
    }

    @Test
    void workerGroupSelectorNarrowsCandidatePoolBeforeRuntimeSelection() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-selected", "pool-selected", "us", Map.of());
        harness.addWorker("worker-other", "pool-other", "us", Map.of());
        Task task = harness.createBatchTask(
                "worker-group-selector",
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

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));

        List<ActiveLeaseRecord> activeLeases = harness.activeLeases(task.getTid());
        assertEquals(1, activeLeases.size());
        assertEquals("worker-selected", activeLeases.getFirst().workerId());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(task.getTid()).getStatus());
        assertEquals(0, harness.stats(task.getTid()).readyCount());
        assertEquals(1, harness.stats(task.getTid()).inflightCount());
        assertTrue(harness.workerRecords(task.getTid(), "worker-other").isEmpty());
        assertFalse(harness.workerManager.hasWorkerExclusiveLease("worker-other"));
    }

    @Test
    void minimumWorkerGateKeepsTaskReadyUntilEnoughEligibleWorkersExist() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-us-1", "us");
        harness.addWorker("worker-gb", "gb");
        Task task = harness.createBatchTask(
                "minimum-worker-gate",
                List.of(harness.item("alpha"), harness.item("beta")),
                0,
                1,
                Map.of(TaskSharedConfig.ROUTING_CODE, "us"),
                2
        );
        assertTrue(harness.taskManager.approveTask(task.getTid()));

        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));

        Task gatedTask = harness.taskManager.getTask(task.getTid());
        TaskWorkStats gatedStats = harness.stats(task.getTid());
        assertEquals(TaskStatus.READY, gatedTask.getStatus());
        assertEquals(2, gatedStats.readyCount());
        assertEquals(0, gatedStats.inflightCount());
        assertTrue(harness.activeLeases(task.getTid()).isEmpty());
        assertEquals(1, harness.selectionReasonCount(task.getTid(), "routing code mismatch"));

        harness.addWorker("worker-us-2", "us");

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));

        List<ActiveLeaseRecord> activeLeases = harness.activeLeases(task.getTid());
        Set<String> leasedWorkers = activeLeases.stream()
                .map(ActiveLeaseRecord::workerId)
                .collect(Collectors.toSet());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(task.getTid()).getStatus());
        assertEquals(2, activeLeases.size());
        assertEquals(Set.of("worker-us-1", "worker-us-2"), leasedWorkers);
        assertEquals(0, harness.stats(task.getTid()).readyCount());
        assertEquals(2, harness.stats(task.getTid()).inflightCount());
    }

    @Test
    void minimumWorkerGateReleasesMatchedWorkerSoAnotherReadyTaskCanCompete() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-us", "us");
        harness.addWorker("worker-gb", "gb");
        Task gatedTask = harness.createBatchTask(
                "minimum-worker-gate-release",
                List.of(harness.item("alpha"), harness.item("beta")),
                0,
                1,
                Map.of(TaskSharedConfig.ROUTING_CODE, "us"),
                2
        );
        Task competingTask = harness.createBatchTask(
                "minimum-worker-gate-competing",
                List.of(harness.item("competing")),
                0,
                1,
                Map.of(TaskSharedConfig.ROUTING_CODE, "us"),
                1
        );
        assertTrue(harness.taskManager.approveTask(gatedTask.getTid()));
        assertTrue(harness.taskManager.approveTask(competingTask.getTid()));

        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(gatedTask.getTid())));

        assertEquals(TaskStatus.READY, harness.taskManager.getTask(gatedTask.getTid()).getStatus());
        assertEquals(2, harness.stats(gatedTask.getTid()).readyCount());
        assertEquals(0, harness.stats(gatedTask.getTid()).inflightCount());
        assertTrue(harness.activeLeases(gatedTask.getTid()).isEmpty());
        assertFalse(harness.workerManager.hasWorkerExclusiveLease("worker-us"));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(competingTask.getTid())));

        List<ActiveLeaseRecord> competingLeases = harness.activeLeases(competingTask.getTid());
        assertEquals(1, competingLeases.size());
        assertEquals("worker-us", competingLeases.getFirst().workerId());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(competingTask.getTid()).getStatus());
        assertEquals(0, harness.stats(competingTask.getTid()).readyCount());
        assertEquals(1, harness.stats(competingTask.getTid()).inflightCount());
        assertEquals(TaskStatus.READY, harness.taskManager.getTask(gatedTask.getTid()).getStatus());
        assertEquals(2, harness.stats(gatedTask.getTid()).readyCount());
        assertEquals(0, harness.stats(gatedTask.getTid()).inflightCount());
    }

    @Test
    void targetWorkerAttributesRemainStableUnderContention() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker(
                "worker-gold",
                "us",
                Map.of("region", "us", "tier", "gold")
        );
        harness.addWorker(
                "worker-silver",
                "us",
                Map.of("region", "us", "tier", "silver")
        );
        harness.addWorker(
                "worker-gold-locked",
                "us",
                Map.of("region", "us", "tier", "gold")
        );
        assertTrue(harness.workerManager.tryAcquireWorkerExclusiveLease("worker-gold-locked"));
        Task task = harness.createBatchTask(
                "target-worker-attributes",
                List.of(harness.item("targeted")),
                0,
                1,
                Map.of(
                        TaskSharedConfig.ROUTING_CODE, "us",
                        TaskSharedConfig.TARGET_WORKER_ATTRIBUTES, Map.of("region", "us", "tier", "gold")
                ),
                1
        );
        assertTrue(harness.taskManager.approveTask(task.getTid()));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));

        List<ActiveLeaseRecord> activeLeases = harness.activeLeases(task.getTid());
        assertEquals(1, activeLeases.size());
        assertEquals("worker-gold", activeLeases.getFirst().workerId());

        assertEquals(1, harness.selectionReasonCount(task.getTid(), "target worker attributes mismatch"));
        assertEquals(1, harness.selectionReasonCount(task.getTid(), "worker locked"));
    }

    @Test
    void targetWorkerIdWaitingTaskDoesNotDriftToBackupWorkerAfterContentionClears() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-target", "us");
        harness.addWorker("worker-backup", "us");
        Task runningTask = harness.createReadyBatchTask(
                "target-worker-id-running",
                List.of(harness.item("running"))
        );
        Task waitingTask = harness.createBatchTask(
                "target-worker-id-waiting",
                List.of(harness.item("waiting")),
                0,
                1,
                Map.of(
                        TaskSharedConfig.ROUTING_CODE, "us",
                        TaskSharedConfig.TARGET_WORKER_ID, "worker-target"
                ),
                1
        );
        assertTrue(harness.taskManager.approveTask(waitingTask.getTid()));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(runningTask.getTid())));
        ActiveLeaseRecord runningLease = harness.activeLeases(runningTask.getTid()).getFirst();
        assertEquals("worker-target", runningLease.workerId());

        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(waitingTask.getTid())));
        assertEquals(TaskStatus.READY, harness.taskManager.getTask(waitingTask.getTid()).getStatus());
        assertEquals(1, harness.stats(waitingTask.getTid()).readyCount());
        assertTrue(harness.activeLeases(waitingTask.getTid()).isEmpty());
        assertTrue(harness.workerRecords(waitingTask.getTid(), "worker-backup").isEmpty());
        assertEquals(1, harness.selectionReasonCount(waitingTask.getTid(), "worker locked"));

        assertTrue(harness.taskManager.ingestTaskResult(
                runningTask.getTid(),
                runningLease.messageId(),
                true,
                "target worker released",
                null,
                java.util.Map.of("source", "target-worker-id")
        ));
        assertEquals(TaskStatus.TERMINAL, harness.taskManager.getTask(runningTask.getTid()).getStatus());

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(waitingTask.getTid())));

        List<ActiveLeaseRecord> waitingLeases = harness.activeLeases(waitingTask.getTid());
        assertEquals(1, waitingLeases.size());
        assertEquals("worker-target", waitingLeases.getFirst().workerId());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(waitingTask.getTid()).getStatus());
        assertTrue(harness.workerManager.hasWorkerExclusiveLease("worker-target"));
        assertFalse(harness.workerManager.hasWorkerExclusiveLease("worker-backup"));
    }
}
