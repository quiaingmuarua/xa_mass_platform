package com.xa.mass.engine;

import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.engine.model.AssignmentRecord;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskWorkerContextContentionTest {

    @Test
    void singleWorkerMultipleContextsSelectsMatchingRouteWithoutRouteDrift() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorkerWithContext("worker-multi", "ctx-gb", "gb");
        harness.addContextToWorker("worker-multi", "ctx-us", "us");
        Task task = harness.createBatchTask(
                "single-worker-multi-context",
                List.of(harness.item("routed")),
                0,
                1,
                Map.of(TaskSharedConfig.ROUTING_CODE, "us"),
                1
        );
        assertTrue(harness.taskManager.approveTask(task.getTid()));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));

        List<ActiveLeaseRecord> activeLeases = harness.activeLeases(task.getTid());
        assertEquals(1, activeLeases.size());
        assertEquals("worker-multi", activeLeases.getFirst().workerId());
        assertEquals("ctx-us", activeLeases.getFirst().workerContextId());
        assertEquals(WorkerContextStatus.IDLE,
                harness.workerManager.getWorkerContextById("ctx-gb").getStatus());
        assertTrue(harness.workerManager.isLocked("worker-multi"));

        List<AssignmentRecord> workerRecords = harness.workerRecords(task.getTid(), "worker-multi");
        assertEquals(2, workerRecords.size());
        assertTrue(workerRecords.stream().anyMatch(record ->
                AssignmentResult.RULE_NOT_MATCH.equals(record.getResult())
                        && "routing code mismatch".equals(record.getReason())
                        && "ctx-gb".equals(record.getWorkerSchedulingSnapshot().getLegacyWorkerContextId())));
        assertTrue(workerRecords.stream().anyMatch(record ->
                AssignmentResult.SUCCESS.equals(record.getResult())
                        && "ctx-us".equals(record.getWorkerSchedulingSnapshot().getLegacyWorkerContextId())));
    }

    @Test
    void secondTaskCannotStealAnotherContextFromAlreadyAssignedWorker() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorkerWithContext("worker-multi", "ctx-us", "us");
        harness.addContextToWorker("worker-multi", "ctx-gb", "gb");
        Task firstTask = harness.createBatchTask(
                "context-lock-first",
                List.of(harness.item("us")),
                0,
                1,
                Map.of(TaskSharedConfig.ROUTING_CODE, "us"),
                1
        );
        Task secondTask = harness.createBatchTask(
                "context-lock-second",
                List.of(harness.item("gb")),
                0,
                1,
                Map.of(TaskSharedConfig.ROUTING_CODE, "gb"),
                1
        );
        assertTrue(harness.taskManager.approveTask(firstTask.getTid()));
        assertTrue(harness.taskManager.approveTask(secondTask.getTid()));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(firstTask.getTid())));
        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(secondTask.getTid())));

        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(firstTask.getTid()).getStatus());
        assertEquals(TaskStatus.READY, harness.taskManager.getTask(secondTask.getTid()).getStatus());
        assertEquals(1, harness.activeLeases(firstTask.getTid()).size());
        assertTrue(harness.activeLeases(secondTask.getTid()).isEmpty());
        assertEquals(1, harness.stats(secondTask.getTid()).readyCount());

        List<AssignmentRecord> secondTaskRecords = harness.workerRecords(secondTask.getTid(), "worker-multi");
        assertFalse(secondTaskRecords.isEmpty());
        assertTrue(secondTaskRecords.stream().allMatch(record ->
                AssignmentResult.CONFLICT.equals(record.getResult())
                        && "worker locked".equals(record.getReason())));
        assertEquals(WorkerContextStatus.IDLE,
                harness.workerManager.getWorkerContextById("ctx-gb").getStatus());
    }

    @Test
    void singleWorkerWithMultipleMatchingContextsDoesNotSatisfyMinimumWorkerGate() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorkerWithContext("worker-multi", "ctx-us-a", "us");
        harness.addContextToWorker("worker-multi", "ctx-us-b", "us");
        Task task = harness.createBatchTask(
                "single-worker-multiple-contexts-min-gate",
                List.of(harness.item("first"), harness.item("second")),
                0,
                2,
                Map.of(TaskSharedConfig.ROUTING_CODE, "us"),
                2
        );
        assertTrue(harness.taskManager.approveTask(task.getTid()));

        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));

        assertEquals(TaskStatus.READY, harness.taskManager.getTask(task.getTid()).getStatus());
        assertEquals(2, harness.stats(task.getTid()).readyCount());
        assertEquals(0, harness.stats(task.getTid()).inflightCount());
        assertTrue(harness.activeLeases(task.getTid()).isEmpty());
        assertEquals(WorkerContextStatus.IDLE,
                harness.workerManager.getWorkerContextById("ctx-us-a").getStatus());
        assertEquals(WorkerContextStatus.IDLE,
                harness.workerManager.getWorkerContextById("ctx-us-b").getStatus());

        List<AssignmentRecord> workerRecords = harness.workerRecords(task.getTid(), "worker-multi");
        assertEquals(1, workerRecords.size());
        assertTrue(workerRecords.stream().anyMatch(record ->
                AssignmentResult.SUCCESS.equals(record.getResult())
                        && "ctx-us-a".equals(record.getWorkerSchedulingSnapshot().getLegacyWorkerContextId())));
    }

    @Test
    void waitingTaskUsesItsOwnRouteContextAfterSharedWorkerIsReleased() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorkerWithContext("worker-multi", "ctx-us", "us");
        harness.addContextToWorker("worker-multi", "ctx-gb", "gb");
        Task firstTask = harness.createBatchTask(
                "shared-worker-release-first",
                List.of(harness.item("us")),
                0,
                1,
                Map.of(TaskSharedConfig.ROUTING_CODE, "us"),
                1
        );
        Task secondTask = harness.createBatchTask(
                "shared-worker-release-second",
                List.of(harness.item("gb")),
                0,
                1,
                Map.of(TaskSharedConfig.ROUTING_CODE, "gb"),
                1
        );
        assertTrue(harness.taskManager.approveTask(firstTask.getTid()));
        assertTrue(harness.taskManager.approveTask(secondTask.getTid()));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(firstTask.getTid())));
        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(secondTask.getTid())));

        ActiveLeaseRecord firstLease = harness.activeLeases(firstTask.getTid()).getFirst();
        assertEquals("ctx-us", firstLease.workerContextId());
        assertEquals(TaskStatus.READY, harness.taskManager.getTask(secondTask.getTid()).getStatus());
        assertEquals(1, harness.stats(secondTask.getTid()).readyCount());

        assertTrue(harness.taskManager.ingestTaskResult(
                firstTask.getTid(),
                firstLease.messageId(),
                true,
                "first route done",
                null,
                java.util.Map.of("source", "shared-worker-release")
        ));

        assertEquals(TaskStatus.TERMINAL, harness.taskManager.getTask(firstTask.getTid()).getStatus());
        assertEquals(WorkerContextStatus.IDLE,
                harness.workerManager.getWorkerContextById("ctx-us").getStatus());
        assertEquals(WorkerContextStatus.IDLE,
                harness.workerManager.getWorkerContextById("ctx-gb").getStatus());

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(secondTask.getTid())));

        List<ActiveLeaseRecord> secondLeases = harness.activeLeases(secondTask.getTid());
        assertEquals(1, secondLeases.size());
        assertEquals("worker-multi", secondLeases.getFirst().workerId());
        assertEquals("ctx-gb", secondLeases.getFirst().workerContextId());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(secondTask.getTid()).getStatus());
        assertEquals(0, harness.stats(secondTask.getTid()).readyCount());
        assertEquals(1, harness.stats(secondTask.getTid()).inflightCount());
        assertEquals(WorkerContextStatus.IDLE,
                harness.workerManager.getWorkerContextById("ctx-us").getStatus());
        assertTrue(harness.workerManager.isLocked("worker-multi"));
    }
}
