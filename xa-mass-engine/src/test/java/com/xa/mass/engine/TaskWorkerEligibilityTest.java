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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskWorkerEligibilityTest {

    @Test
    void workerPrefilterExcludesUnreachableLockedOccupiedAndRoutingMismatchCandidates() {
        Map<String, WorkerReachabilityState> reachability = Map.of(
                "worker-unreachable", WorkerReachabilityState.OFFLINE
        );
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness(
                workerId -> reachability.getOrDefault(workerId, WorkerReachabilityState.ONLINE)
        );
        harness.addWorkerWithContext("worker-unreachable", "ctx-unreachable", "us");
        harness.addWorkerWithContext("worker-locked", "ctx-locked", "us");
        harness.addWorkerWithContext("worker-occupied", "ctx-occupied", "us", WorkerContextStatus.OCCUPIED);
        harness.addWorkerWithContext("worker-routing-mismatch", "ctx-routing-mismatch", "gb");
        harness.addWorkerWithContext("worker-eligible", "ctx-eligible", "us");
        assertTrue(harness.workerManager.tryLockWorker("worker-locked"));

        Task task = harness.createReadyBatchTask("eligibility", List.of(harness.item("eligible")));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));

        List<ActiveLeaseRecord> activeLeases = harness.activeLeases(task.getTid());
        assertEquals(1, activeLeases.size());
        assertEquals("worker-eligible", activeLeases.getFirst().workerId());
        assertEquals("ctx-eligible", activeLeases.getFirst().workerContextId());
        assertEquals(1, harness.successfulMessageAssignments(task.getTid(), "worker-eligible"));

        assertRejected(harness, task.getTid(), "worker-unreachable",
                AssignmentResult.RESOURCE_UNAVAILABLE, "worker transport unreachable");
        assertRejected(harness, task.getTid(), "worker-locked",
                AssignmentResult.CONFLICT, "worker locked");
        assertRejected(harness, task.getTid(), "worker-occupied",
                AssignmentResult.RESOURCE_UNAVAILABLE, "workerContext not allocatable");
        assertRejected(harness, task.getTid(), "worker-routing-mismatch",
                AssignmentResult.RULE_NOT_MATCH, "routing code mismatch");
        assertFalse(harness.record(task.getTid(), "worker-eligible").getRuleEvaluations().isEmpty());
    }

    @Test
    void activeContentionExcludesWorkerAfterTransportReachabilityDropsAndUsesBackupWorker() {
        Map<String, WorkerReachabilityState> reachability = new HashMap<>();
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness(
                workerId -> reachability.getOrDefault(workerId, WorkerReachabilityState.ONLINE)
        );
        harness.addWorkerWithContext("worker-primary", "ctx-primary", "us");
        harness.addWorkerWithContext("worker-backup", "ctx-backup", "us");
        Task firstTask = harness.createReadyBatchTask("reachability-first", List.of(harness.item("first")));
        Task secondTask = harness.createReadyBatchTask("reachability-second", List.of(harness.item("second")));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(firstTask.getTid())));
        ActiveLeaseRecord firstLease = harness.activeLeases(firstTask.getTid()).getFirst();
        assertEquals("worker-primary", firstLease.workerId());

        reachability.put("worker-primary", WorkerReachabilityState.OFFLINE);

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(secondTask.getTid())));

        List<ActiveLeaseRecord> secondLeases = harness.activeLeases(secondTask.getTid());
        assertEquals(1, secondLeases.size());
        assertEquals("worker-backup", secondLeases.getFirst().workerId());
        assertEquals("ctx-backup", secondLeases.getFirst().workerContextId());
        assertEquals(1, harness.activeLeases(firstTask.getTid()).size());
        assertRejected(harness, secondTask.getTid(), "worker-primary",
                AssignmentResult.RESOURCE_UNAVAILABLE, "worker transport unreachable");
    }

    @Test
    void minimumWorkerGateUsesReachableEligibilityAndDoesNotHalfDispatchWhenWorkerDrops() {
        Map<String, WorkerReachabilityState> reachability = new HashMap<>();
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness(
                workerId -> reachability.getOrDefault(workerId, WorkerReachabilityState.ONLINE)
        );
        harness.addWorkerWithContext("worker-stable", "ctx-stable", "us");
        harness.addWorkerWithContext("worker-dropped", "ctx-dropped", "us");
        Task task = harness.createBatchTask(
                "minimum-worker-reachability-drop",
                List.of(harness.item("alpha"), harness.item("beta")),
                0,
                1,
                Map.of(TaskSharedConfig.ROUTING_CODE, "us"),
                2
        );
        assertTrue(harness.taskManager.approveTask(task.getTid()));

        reachability.put("worker-dropped", WorkerReachabilityState.OFFLINE);

        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));

        Task updatedTask = harness.taskManager.getTask(task.getTid());
        TaskWorkStats stats = harness.stats(task.getTid());
        assertEquals(TaskStatus.READY, updatedTask.getStatus());
        assertEquals(2, stats.readyCount());
        assertEquals(0, stats.inflightCount());
        assertTrue(harness.activeLeases(task.getTid()).isEmpty());
        assertEquals(WorkerContextStatus.IDLE,
                harness.workerManager.getWorkerContextById("ctx-stable").getStatus());
        assertEquals(WorkerContextStatus.IDLE,
                harness.workerManager.getWorkerContextById("ctx-dropped").getStatus());
        assertRejected(harness, task.getTid(), "worker-dropped",
                AssignmentResult.RESOURCE_UNAVAILABLE, "worker transport unreachable");

        reachability.put("worker-dropped", WorkerReachabilityState.ONLINE);

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));

        List<ActiveLeaseRecord> activeLeases = harness.activeLeases(task.getTid());
        assertEquals(2, activeLeases.size());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(task.getTid()).getStatus());
        assertEquals(0, harness.stats(task.getTid()).readyCount());
        assertEquals(2, harness.stats(task.getTid()).inflightCount());
    }

    private void assertRejected(TaskSchedulingTestHarness harness,
                                String taskId,
                                String workerId,
                                AssignmentResult expectedResult,
                                String expectedReason) {
        AssignmentRecord record = harness.record(taskId, workerId);
        assertEquals(expectedResult, record.getResult());
        assertEquals(expectedReason, record.getReason());
    }
}
