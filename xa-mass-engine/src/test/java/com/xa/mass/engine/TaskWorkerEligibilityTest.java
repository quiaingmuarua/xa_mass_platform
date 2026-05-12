package com.xa.mass.engine;

import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.AssignmentRecord;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import org.junit.jupiter.api.Test;

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
