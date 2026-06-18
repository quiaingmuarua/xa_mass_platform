package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.engine.assignment.DefaultWorkerBudgetPolicy;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.worker.runtime.evidence.WorkerOccupancyState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskSchedulingContentionTest {

    @Test
    void multipleReadyBatchTasksCompeteForSingleContextWithoutDoubleAssignment() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-single", "us");
        Task firstTask = harness.createReadyBatchTask("contention-first", List.of(harness.item("first")));
        Task secondTask = harness.createReadyBatchTask("contention-second", List.of(harness.item("second")));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(firstTask.getTid())));
        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(secondTask.getTid())));

        Task updatedFirstTask = harness.taskManager.getTask(firstTask.getTid());
        Task updatedSecondTask = harness.taskManager.getTask(secondTask.getTid());
        TaskWorkStats firstStats = harness.stats(firstTask.getTid());
        TaskWorkStats secondStats = harness.stats(secondTask.getTid());
        List<ActiveLeaseRecord> firstLeases = harness.activeLeases(firstTask.getTid());

        assertEquals(TaskStatus.RUNNING, updatedFirstTask.getStatus());
        assertEquals(TaskStatus.READY, updatedSecondTask.getStatus());
        assertEquals(0, firstStats.readyCount());
        assertEquals(1, firstStats.inflightCount());
        assertEquals(1, secondStats.readyCount());
        assertEquals(0, secondStats.inflightCount());
        assertEquals(1, firstLeases.size());
        assertEquals("worker-single", firstLeases.getFirst().workerId());

        assertEquals(1, harness.selectionReasonCount(secondTask.getTid(), "worker locked"));
        assertEquals(1, harness.successfulMessageAssignments(firstTask.getTid(), "worker-single"));
        assertEquals(0, harness.successfulMessageAssignments(secondTask.getTid(), "worker-single"));
        assertTrue(harness.workerManager.hasWorkerExclusiveLease("worker-single"));
    }

    @Test
    void backgroundTasksShareStatelessWorkerUpToDeclaredCapacity() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addStatelessWorker("worker-background", 2);
        Task firstTask = createReadyBackgroundTask(harness, "background-first", "first");
        Task secondTask = createReadyBackgroundTask(harness, "background-second", "second");
        Task thirdTask = createReadyBackgroundTask(harness, "background-third", "third");

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(firstTask.getTid())));
        assertEquals(WorkerOccupancyState.OCCUPIED,
                harness.workerManager.getWorkerLoad("worker-background").occupancyState());
        assertEquals(1, harness.workerManager.getWorkerLoad("worker-background").activeLeaseCount());

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(secondTask.getTid())));
        assertEquals(WorkerOccupancyState.CAPACITY_FULL,
                harness.workerManager.getWorkerLoad("worker-background").occupancyState());

        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(thirdTask.getTid())));

        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(firstTask.getTid()).getStatus());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(secondTask.getTid()).getStatus());
        assertEquals(TaskStatus.READY, harness.taskManager.getTask(thirdTask.getTid()).getStatus());
        assertFalse(harness.workerManager.hasWorkerExclusiveLease("worker-background"));
        assertEquals(2, harness.workerManager.getWorkerLoad("worker-background").activeLeaseCount());

        List<ActiveLeaseRecord> firstLeases = harness.activeLeases(firstTask.getTid());
        List<ActiveLeaseRecord> secondLeases = harness.activeLeases(secondTask.getTid());
        assertEquals(1, firstLeases.size());
        assertEquals(1, secondLeases.size());
        assertEquals("worker-background", firstLeases.getFirst().workerId());
        assertEquals("worker-background", secondLeases.getFirst().workerId());

        assertEquals(1, harness.selectionReasonCount(thirdTask.getTid(), "worker capacity unavailable"));
    }

    @Test
    void multipleReadyBatchTasksCompeteForWorkerPoolWithoutDuplicateLeaseOrLostReadyWork() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-a", "us");
        harness.addWorker("worker-b", "us");
        Task firstTask = harness.createReadyBatchTask("pool-first", List.of(harness.item("first")));
        Task secondTask = harness.createReadyBatchTask("pool-second", List.of(harness.item("second")));
        Task thirdTask = harness.createReadyBatchTask("pool-third", List.of(harness.item("third")));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(firstTask.getTid())));
        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(secondTask.getTid())));
        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(thirdTask.getTid())));

        List<ActiveLeaseRecord> firstLeases = harness.activeLeases(firstTask.getTid());
        List<ActiveLeaseRecord> secondLeases = harness.activeLeases(secondTask.getTid());
        Set<String> leasedWorkers = java.util.stream.Stream.concat(firstLeases.stream(), secondLeases.stream())
                .map(ActiveLeaseRecord::workerId)
                .collect(Collectors.toSet());
        assertEquals(1, firstLeases.size());
        assertEquals(1, secondLeases.size());
        assertEquals(Set.of("worker-a", "worker-b"), leasedWorkers);
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(firstTask.getTid()).getStatus());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(secondTask.getTid()).getStatus());
        assertEquals(TaskStatus.READY, harness.taskManager.getTask(thirdTask.getTid()).getStatus());
        assertEquals(1, harness.stats(thirdTask.getTid()).readyCount());
        assertTrue(harness.activeLeases(thirdTask.getTid()).isEmpty());

        assertEquals(2, harness.selectionReasonCount(thirdTask.getTid(), "worker locked"));

        ActiveLeaseRecord firstLease = firstLeases.getFirst();
        assertTrue(harness.taskManager.ingestTaskResult(
                firstTask.getTid(),
                firstLease.messageId(),
                true,
                "first done",
                null,
                java.util.Map.of("source", "worker-pool")
        ));

        assertEquals(TaskStatus.TERMINAL, harness.taskManager.getTask(firstTask.getTid()).getStatus());
        assertFalse(harness.workerManager.hasWorkerExclusiveLease(firstLease.workerId()));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(thirdTask.getTid())));

        List<ActiveLeaseRecord> thirdLeases = harness.activeLeases(thirdTask.getTid());
        assertEquals(1, thirdLeases.size());
        assertEquals(firstLease.workerId(), thirdLeases.getFirst().workerId());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(thirdTask.getTid()).getStatus());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(secondTask.getTid()).getStatus());
        assertEquals(0, harness.stats(thirdTask.getTid()).readyCount());
        assertEquals(1, harness.stats(thirdTask.getTid()).inflightCount());
    }

    @Test
    void largeBulkTaskIsCappedAndLeavesWorkersForInteractiveTask() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        for (int i = 0; i < 25; i++) {
            harness.addWorker("worker-budget-" + i, "us");
        }
        List<java.util.Map<String, Object>> bulkItems = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            bulkItems.add(harness.item("bulk-" + i));
        }
        Task bulkTask = harness.createReadyBatchTask("bulk-budget-cap", bulkItems);
        Task interactiveTask = harness.createSessionTask(
                "interactive-after-bulk-budget",
                List.of(harness.item("interactive")),
                0,
                1
        );
        assertTrue(harness.taskManager.approveTask(interactiveTask.getTid()));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(bulkTask.getTid())));
        assertEquals(DefaultWorkerBudgetPolicy.DEFAULT_BULK_MAX_WORKERS, harness.activeLeases(bulkTask.getTid()).size());
        assertEquals(80, harness.stats(bulkTask.getTid()).readyCount());
        assertEquals(DefaultWorkerBudgetPolicy.DEFAULT_BULK_MAX_WORKERS, harness.stats(bulkTask.getTid()).inflightCount());

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(interactiveTask.getTid())));

        List<ActiveLeaseRecord> interactiveLeases = harness.activeLeases(interactiveTask.getTid());
        Set<String> bulkWorkers = harness.activeLeases(bulkTask.getTid()).stream()
                .map(ActiveLeaseRecord::workerId)
                .collect(Collectors.toSet());
        assertEquals(1, interactiveLeases.size());
        assertFalse(bulkWorkers.contains(interactiveLeases.getFirst().workerId()));
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(interactiveTask.getTid()).getStatus());
        assertEquals(0, harness.stats(interactiveTask.getTid()).readyCount());
        assertEquals(1, harness.stats(interactiveTask.getTid()).inflightCount());
    }

    @Test
    void batchSizeChangesDispatchWorkerCountWithoutLosingReadyWork() {
        TaskSchedulingTestHarness singleItemBatch = new TaskSchedulingTestHarness();
        for (int i = 0; i < 4; i++) {
            singleItemBatch.addWorker("worker-single-batch-" + i, "us");
        }
        Task batchSizeOneTask = singleItemBatch.createBatchTask(
                "batch-size-one",
                List.of(
                        singleItemBatch.item("one-a"),
                        singleItemBatch.item("one-b"),
                        singleItemBatch.item("one-c"),
                        singleItemBatch.item("one-d")
                ),
                0,
                1
        );
        assertTrue(singleItemBatch.taskManager.approveTask(batchSizeOneTask.getTid()));

        assertTrue(singleItemBatch.assignListener.onTaskAssign(
                singleItemBatch.taskManager.getTask(batchSizeOneTask.getTid())));

        Set<String> singleBatchWorkers = singleItemBatch.activeLeases(batchSizeOneTask.getTid()).stream()
                .map(ActiveLeaseRecord::workerId)
                .collect(Collectors.toSet());
        assertEquals(4, singleBatchWorkers.size());
        assertEquals(0, singleItemBatch.stats(batchSizeOneTask.getTid()).readyCount());
        assertEquals(4, singleItemBatch.stats(batchSizeOneTask.getTid()).inflightCount());

        TaskSchedulingTestHarness twoItemBatch = new TaskSchedulingTestHarness();
        for (int i = 0; i < 4; i++) {
            twoItemBatch.addWorker("worker-two-batch-" + i, "us");
        }
        Task batchSizeTwoTask = twoItemBatch.createBatchTask(
                "batch-size-two",
                List.of(
                        twoItemBatch.item("two-a"),
                        twoItemBatch.item("two-b"),
                        twoItemBatch.item("two-c"),
                        twoItemBatch.item("two-d")
                ),
                0,
                2
        );
        assertTrue(twoItemBatch.taskManager.approveTask(batchSizeTwoTask.getTid()));

        assertTrue(twoItemBatch.assignListener.onTaskAssign(
                twoItemBatch.taskManager.getTask(batchSizeTwoTask.getTid())));

        Set<String> twoBatchWorkers = twoItemBatch.activeLeases(batchSizeTwoTask.getTid()).stream()
                .map(ActiveLeaseRecord::workerId)
                .collect(Collectors.toSet());
        assertEquals(2, twoBatchWorkers.size());
        assertEquals(0, twoItemBatch.stats(batchSizeTwoTask.getTid()).readyCount());
        assertEquals(4, twoItemBatch.stats(batchSizeTwoTask.getTid()).inflightCount());
    }

    @Test
    void pausedBlockedAndTerminatedTasksDoNotDispatchEvenWhenReadyWorkExists() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-gate", "us");
        Task pausedTask = harness.createReadyBatchTask("paused-gate", List.of(harness.item("paused")));
        Task blockedTask = harness.createReadyBatchTask("blocked-gate", List.of(harness.item("blocked")));
        Task terminalTask = harness.createReadyBatchTask("terminal-gate", List.of(harness.item("terminal")));

        assertTrue(harness.taskManager.pauseTask(pausedTask.getTid()));
        assertTrue(harness.taskManager.blockTask(blockedTask.getTid()));
        assertTrue(harness.taskManager.cancelTask(terminalTask.getTid()));

        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(pausedTask.getTid())));
        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(blockedTask.getTid())));
        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(terminalTask.getTid())));

        assertEquals(1, harness.stats(pausedTask.getTid()).readyCount());
        assertEquals(1, harness.stats(blockedTask.getTid()).readyCount());
        assertEquals(0, harness.stats(terminalTask.getTid()).totalCount());
        assertTrue(harness.activeLeases(pausedTask.getTid()).isEmpty());
        assertTrue(harness.activeLeases(blockedTask.getTid()).isEmpty());
        assertTrue(harness.activeLeases(terminalTask.getTid()).isEmpty());
        assertEquals(TaskStatus.PAUSED, harness.taskManager.getTask(pausedTask.getTid()).getStatus());
        assertEquals(TaskStatus.BLOCKED, harness.taskManager.getTask(blockedTask.getTid()).getStatus());
        assertEquals(TaskStatus.TERMINAL, harness.taskManager.getTask(terminalTask.getTid()).getStatus());
    }

    @Test
    void pausedWaitingTaskDoesNotAcquireReleasedResourceUntilResumed() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-shared", "us");
        Task runningTask = harness.createReadyBatchTask("pause-wait-running", List.of(harness.item("running")));
        Task waitingTask = harness.createReadyBatchTask("pause-wait-waiting", List.of(harness.item("waiting")));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(runningTask.getTid())));
        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(waitingTask.getTid())));
        assertTrue(harness.taskManager.pauseTask(waitingTask.getTid()));
        ActiveLeaseRecord runningLease = harness.activeLeases(runningTask.getTid()).getFirst();

        assertTrue(harness.taskManager.ingestTaskResult(
                runningTask.getTid(),
                runningLease.messageId(),
                true,
                "running task done",
                null,
                java.util.Map.of("source", "pause-wait")
        ));

        assertEquals(TaskStatus.TERMINAL, harness.taskManager.getTask(runningTask.getTid()).getStatus());
        assertFalse(harness.workerManager.hasWorkerExclusiveLease("worker-shared"));
        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(waitingTask.getTid())));
        assertEquals(TaskStatus.PAUSED, harness.taskManager.getTask(waitingTask.getTid()).getStatus());
        assertEquals(1, harness.stats(waitingTask.getTid()).readyCount());
        assertTrue(harness.activeLeases(waitingTask.getTid()).isEmpty());

        assertTrue(harness.taskManager.resumeTask(waitingTask.getTid()));
        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(waitingTask.getTid())));

        List<ActiveLeaseRecord> waitingLeases = harness.activeLeases(waitingTask.getTid());
        assertEquals(1, waitingLeases.size());
        assertEquals("worker-shared", waitingLeases.getFirst().workerId());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(waitingTask.getTid()).getStatus());
    }

    @Test
    void blockedWaitingTaskDoesNotAcquireReleasedResourceAndNextReadyTaskCanCompete() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-shared", "us");
        Task runningTask = harness.createReadyBatchTask("block-wait-running", List.of(harness.item("running")));
        Task blockedTask = harness.createReadyBatchTask("block-wait-blocked", List.of(harness.item("blocked")));
        Task nextReadyTask = harness.createReadyBatchTask("block-wait-next-ready", List.of(harness.item("next")));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(runningTask.getTid())));
        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(blockedTask.getTid())));
        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(nextReadyTask.getTid())));
        assertTrue(harness.taskManager.blockTask(blockedTask.getTid()));
        ActiveLeaseRecord runningLease = harness.activeLeases(runningTask.getTid()).getFirst();

        assertTrue(harness.taskManager.ingestTaskResult(
                runningTask.getTid(),
                runningLease.messageId(),
                true,
                "running task done",
                null,
                java.util.Map.of("source", "blocked-wait")
        ));

        assertEquals(TaskStatus.TERMINAL, harness.taskManager.getTask(runningTask.getTid()).getStatus());
        assertFalse(harness.workerManager.hasWorkerExclusiveLease("worker-shared"));
        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(blockedTask.getTid())));
        assertEquals(TaskStatus.BLOCKED, harness.taskManager.getTask(blockedTask.getTid()).getStatus());
        assertEquals(1, harness.stats(blockedTask.getTid()).readyCount());
        assertTrue(harness.activeLeases(blockedTask.getTid()).isEmpty());

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(nextReadyTask.getTid())));

        List<ActiveLeaseRecord> nextReadyLeases = harness.activeLeases(nextReadyTask.getTid());
        assertEquals(1, nextReadyLeases.size());
        assertEquals("worker-shared", nextReadyLeases.getFirst().workerId());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(nextReadyTask.getTid()).getStatus());
        assertEquals(0, harness.stats(nextReadyTask.getTid()).readyCount());
        assertEquals(1, harness.stats(nextReadyTask.getTid()).inflightCount());
        assertTrue(harness.workerManager.hasWorkerExclusiveLease("worker-shared"));
    }

    private Task createReadyBackgroundTask(TaskSchedulingTestHarness harness, String sourceRef, String target) {
        Task task = harness.createBatchTask(
                sourceRef,
                List.of(harness.item(target)),
                0,
                1,
                java.util.Map.of(TaskSharedConfig.ROUTING_CODE, ""),
                0
        );
        task.getExecutionSpec().setForeground(false);
        assertTrue(harness.taskManager.updateTask(task));
        assertTrue(harness.taskManager.approveTask(task.getTid()));
        return harness.taskManager.getTask(task.getTid());
    }
}
