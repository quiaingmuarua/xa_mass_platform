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
        assertEquals(WorkerContextStatus.OCCUPIED,
                harness.workerManager.getWorkerContextById("ctx-us").getStatus());

        List<AssignmentRecord> workerRecords = harness.workerRecords(task.getTid(), "worker-multi");
        assertEquals(2, workerRecords.size());
        assertTrue(workerRecords.stream().anyMatch(record ->
                AssignmentResult.RULE_NOT_MATCH.equals(record.getResult())
                        && "routing code mismatch".equals(record.getReason())
                        && "ctx-gb".equals(record.getWorkerContextSnapshot().getWorkerContextId())));
        assertTrue(workerRecords.stream().anyMatch(record ->
                AssignmentResult.SUCCESS.equals(record.getResult())
                        && "ctx-us".equals(record.getWorkerContextSnapshot().getWorkerContextId())));
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
}
