package com.xa.mass.engine;

import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.engine.model.AssignmentRecord;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.TaskWorkStats;
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
    void minimumWorkerGateKeepsTaskReadyUntilEnoughEligibleWorkersExist() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorkerWithContext("worker-us-1", "ctx-us-1", "us");
        harness.addWorkerWithContext("worker-gb", "ctx-gb", "gb");
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
        AssignmentRecord routingMismatch = harness.record(task.getTid(), "worker-gb");
        assertEquals(AssignmentResult.RULE_NOT_MATCH, routingMismatch.getResult());
        assertEquals("routing code mismatch", routingMismatch.getReason());

        harness.addWorkerWithContext("worker-us-2", "ctx-us-2", "us");

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
        harness.addWorkerWithContext("worker-us", "ctx-us", "us");
        harness.addWorkerWithContext("worker-gb", "ctx-gb", "gb");
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
        assertEquals(WorkerContextStatus.IDLE,
                harness.workerManager.getWorkerContextById("ctx-us").getStatus());

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(competingTask.getTid())));

        List<ActiveLeaseRecord> competingLeases = harness.activeLeases(competingTask.getTid());
        assertEquals(1, competingLeases.size());
        assertEquals("worker-us", competingLeases.getFirst().workerId());
        assertEquals("ctx-us", competingLeases.getFirst().workerContextId());
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
        harness.addWorkerWithContext(
                "worker-gold",
                "ctx-gold",
                "us",
                Map.of("region", "us", "tier", "gold")
        );
        harness.addWorkerWithContext(
                "worker-silver",
                "ctx-silver",
                "us",
                Map.of("region", "us", "tier", "silver")
        );
        harness.addWorkerWithContext(
                "worker-gold-locked",
                "ctx-gold-locked",
                "us",
                Map.of("region", "us", "tier", "gold")
        );
        assertTrue(harness.workerManager.tryLockWorker("worker-gold-locked"));
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
        assertEquals("ctx-gold", activeLeases.getFirst().workerContextId());

        AssignmentRecord silverRecord = harness.record(task.getTid(), "worker-silver");
        assertEquals(AssignmentResult.RULE_NOT_MATCH, silverRecord.getResult());
        assertEquals("target worker attributes mismatch", silverRecord.getReason());

        AssignmentRecord lockedGoldRecord = harness.record(task.getTid(), "worker-gold-locked");
        assertEquals(AssignmentResult.CONFLICT, lockedGoldRecord.getResult());
        assertEquals("worker locked", lockedGoldRecord.getReason());
    }

    @Test
    void targetWorkerIdWaitingTaskDoesNotDriftToBackupWorkerAfterContentionClears() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorkerWithContext("worker-target", "ctx-target", "us");
        harness.addWorkerWithContext("worker-backup", "ctx-backup", "us");
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
        assertEquals("ctx-target", runningLease.workerContextId());

        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(waitingTask.getTid())));
        assertEquals(TaskStatus.READY, harness.taskManager.getTask(waitingTask.getTid()).getStatus());
        assertEquals(1, harness.stats(waitingTask.getTid()).readyCount());
        assertTrue(harness.activeLeases(waitingTask.getTid()).isEmpty());
        assertTrue(harness.workerRecords(waitingTask.getTid(), "worker-backup").isEmpty());
        AssignmentRecord targetLockedRecord = harness.record(waitingTask.getTid(), "worker-target");
        assertEquals(AssignmentResult.CONFLICT, targetLockedRecord.getResult());
        assertEquals("worker locked", targetLockedRecord.getReason());

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
        assertEquals("ctx-target", waitingLeases.getFirst().workerContextId());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(waitingTask.getTid()).getStatus());
        assertTrue(harness.workerManager.isLocked("worker-target"));
        assertEquals(WorkerContextStatus.IDLE,
                harness.workerManager.getWorkerContextById("ctx-backup").getStatus());
    }
}
