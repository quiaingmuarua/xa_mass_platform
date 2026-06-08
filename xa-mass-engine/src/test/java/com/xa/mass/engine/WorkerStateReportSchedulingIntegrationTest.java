package com.xa.mass.engine;

import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.model.AssignmentRecord;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.worker.runtime.command.WorkerCommandAcknowledgement;
import com.xa.mass.worker.runtime.command.WorkerCommandRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerStateReportSchedulingIntegrationTest {

    @Test
    void drainingStateReportExcludesWorkerThenAvailableStateAllowsDispatchAgain() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-draining", "us");
        harness.addWorker("worker-backup", "us");

        harness.applyWorkerStateReport("worker-draining", 1, "DRAINING", "maintenance");

        Task firstTask = harness.createReadyBatchTask("state-report-draining-first", List.of(harness.item("first")));
        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(firstTask.getTid())));

        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(firstTask.getTid()).getStatus());
        List<ActiveLeaseRecord> firstLeases = harness.activeLeases(firstTask.getTid());
        assertEquals(1, firstLeases.size());
        assertEquals("worker-backup", firstLeases.getFirst().workerId());
        assertEquals(0, harness.successfulMessageAssignments(firstTask.getTid(), "worker-draining"));
        assertFalse(hasDispatchBinding(harness, firstTask.getTid(), "worker-draining"));
        assertRejected(harness, firstTask.getTid(), "worker-draining",
                AssignmentResult.RESOURCE_UNAVAILABLE, "worker unavailable");

        harness.applyWorkerStateReport("worker-draining", 2, "AVAILABLE", "resumed");

        Task secondTask = harness.createReadyBatchTask("state-report-draining-second", List.of(harness.item("second")));
        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(secondTask.getTid())));

        List<ActiveLeaseRecord> secondLeases = harness.activeLeases(secondTask.getTid());
        assertEquals(1, secondLeases.size());
        assertEquals("worker-draining", secondLeases.getFirst().workerId());
        assertEquals(1, harness.successfulMessageAssignments(secondTask.getTid(), "worker-draining"));
    }

    @Test
    void availableStateReportDoesNotClearCommandDrainForScheduling() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-command-drained", "us");
        harness.addWorker("worker-backup", "us");

        assertTrue(harness.workerControlService.requestWorkerCommand(WorkerCommandRequest.builder(
                        "cmd-drain-1", "worker-command-drained", "DRAIN")
                .requester("operator")
                .build()).success());
        assertTrue(harness.workerControlService.applyWorkerCommandAcknowledgement(
                WorkerCommandAcknowledgement.deliveryAccepted("cmd-drain-1", "accepted")).success());
        harness.applyWorkerStateReport("worker-command-drained", 1, "AVAILABLE", "state ready");

        Task task = harness.createReadyBatchTask("command-drain-state-available", List.of(harness.item("work")));
        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));

        List<ActiveLeaseRecord> leases = harness.activeLeases(task.getTid());
        assertEquals(1, leases.size());
        assertEquals("worker-backup", leases.getFirst().workerId());
        assertEquals(0, harness.successfulMessageAssignments(task.getTid(), "worker-command-drained"));
        assertFalse(hasDispatchBinding(harness, task.getTid(), "worker-command-drained"));
        assertRejected(harness, task.getTid(), "worker-command-drained",
                AssignmentResult.RESOURCE_UNAVAILABLE, "worker unavailable");
    }

    private static void assertRejected(TaskSchedulingTestHarness harness,
                                       String taskId,
                                       String workerId,
                                       AssignmentResult expectedResult,
                                       String expectedReason) {
        AssignmentRecord record = harness.record(taskId, workerId);
        assertEquals(expectedResult, record.getResult());
        assertEquals(expectedReason, record.getReason());
    }

    private static boolean hasDispatchBinding(TaskSchedulingTestHarness harness, String taskId, String workerId) {
        return harness.dispatches.stream()
                .filter(binding -> taskId.equals(binding.taskId()))
                .map(TaskDispatchBinding::workerId)
                .anyMatch(workerId::equals);
    }
}
