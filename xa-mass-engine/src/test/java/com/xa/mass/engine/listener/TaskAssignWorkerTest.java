package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.runtime.TaskRuntimeProfileResolver;
import com.xa.mass.engine.runtime.TaskRuntimeRetryPolicyResolver;
import com.xa.mass.engine.util.TraceEventLogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TaskAssignWorkerTest {

    private TaskAssignWorker worker;
    private List<Task> assigned;

    /**
     * Stub TaskWorkerAssignListener that records tasks and transitions them to RUNNING,
     * simulating a successful worker assignment. Without the READY->RUNNING transition,
     * the worker treats the task as unassigned and schedules a retry, so assignment-queue
     * notifications would never fire.
     */
    private TaskWorkerAssignListener recordingListener(List<Task> sink) {
        TaskWorkerAssignListener stub = mock(TaskWorkerAssignListener.class);
        doAnswer(inv -> {
            Task t = inv.getArgument(0);
            t.transitionTo(TaskStatus.RUNNING);
            sink.add(t);
            return true;
        }).when(stub).onTaskAssign(any());
        return stub;
    }

    @BeforeEach
    void setUp() {
        assigned = new ArrayList<>();
        worker = new TaskAssignWorker(recordingListener(assigned));
        worker.start();
    }

    @AfterEach
    void tearDown() {
        worker.stop();
    }

    @Test
    void submittedReadyTaskIsProcessed() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        worker.addAssignmentQueueListener(new TaskAssignmentQueueListener() {
            @Override public void onTaskAssignmentProcessed(Task t) { latch.countDown(); }
            @Override public void onAssignmentQueueDrained() {}
        });

        Task task = readyTask("t1");
        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertEquals(TaskAssignWorker.SubmitResult.ACCEPTED, worker.submitDetailed(task));

            assertTrue(latch.await(3, TimeUnit.SECONDS), "Task should be processed within 3s");
            assertEquals(1, assigned.size());
            assertEquals("t1", assigned.get(0).getTid());
            capture.assertHasEvent("ASSIGNMENT_QUEUE_SNAPSHOT", mdc ->
                    "t1".equals(mdc.get("taskId"))
                            && "SUBMITTED".equals(mdc.get("queueAction"))
                            && "BULK".equals(mdc.get("dispatchLane"))
                            && "TaskAssignWorker".equals(mdc.get("source")));
            capture.assertHasEvent("ASSIGNMENT_QUEUE_SNAPSHOT", mdc ->
                    "t1".equals(mdc.get("taskId"))
                            && "PROCESSED".equals(mdc.get("queueAction"))
                            && "TaskAssignWorker".equals(mdc.get("source")));
        }
    }

    @Test
    void readyTaskWithoutMatchIsRetriedUntilAssignmentSucceeds() throws InterruptedException {
        worker.stop();

        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch assignedLatch = new CountDownLatch(1);
        TaskWorkerAssignListener retryingListener = mock(TaskWorkerAssignListener.class);
        doAnswer(invocation -> {
            Task task = invocation.getArgument(0);
            if (attempts.incrementAndGet() >= 2) {
                task.transitionTo(TaskStatus.RUNNING);
                assignedLatch.countDown();
                return true;
            }
            return false;
        }).when(retryingListener).onTaskAssign(any());

        worker = new TaskAssignWorker(retryingListener, 50L);
        worker.start();

        Task task = readyTask("retry");
        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertEquals(TaskAssignWorker.SubmitResult.ACCEPTED, worker.submitDetailed(task));

            assertTrue(assignedLatch.await(3, TimeUnit.SECONDS), "READY task should be retried until assignment succeeds");
            assertEquals(TaskStatus.RUNNING, task.getStatus());
            assertEquals(2, attempts.get());
            verify(retryingListener, atLeast(2)).onTaskAssign(same(task));
            assertTrue(awaitCondition(() -> hasRetryDelayEvent(capture, "retry", "50")),
                    "retry scheduling trace should be captured before assertions run");
            assertTrue(awaitCondition(() -> hasQueueSnapshotEvent(capture, "retry", "RETRY_SCHEDULED",
                    mdc -> "1".equals(mdc.get("scheduledRetryCount")))),
                    "retry-scheduled queue snapshot should be captured before assertions run");
            assertTrue(awaitCondition(() -> hasQueueSnapshotEvent(capture, "retry", "RETRY_ENQUEUED",
                    mdc -> "TaskAssignWorker".equals(mdc.get("source")))),
                    "retry-enqueued queue snapshot should be captured before assertions run");
        }
    }

    @Test
    void runningTaskWithoutImmediateSlotIsRetriedUntilAssignmentSucceeds() throws InterruptedException {
        worker.stop();

        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch assignedLatch = new CountDownLatch(1);
        TaskWorkerAssignListener retryingListener = mock(TaskWorkerAssignListener.class);
        doAnswer(invocation -> {
            Task task = invocation.getArgument(0);
            if (attempts.incrementAndGet() >= 2) {
                assignedLatch.countDown();
                return true;
            }
            return false;
        }).when(retryingListener).onTaskAssign(any());

        worker = new TaskAssignWorker(retryingListener, 50L);
        worker.start();

        Task task = runningTask("running-retry");
        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertEquals(TaskAssignWorker.SubmitResult.ACCEPTED, worker.submitDetailed(task));

            assertTrue(assignedLatch.await(3, TimeUnit.SECONDS), "RUNNING task should be retried until replenishment succeeds");
            assertEquals(TaskStatus.RUNNING, task.getStatus());
            assertEquals(2, attempts.get());
            verify(retryingListener, atLeast(2)).onTaskAssign(same(task));
            capture.assertHasEvent("ASSIGNMENT_RETRY_SCHEDULED", mdc ->
                    "running-retry".equals(mdc.get("taskId"))
                            && "RUNNING".equals(mdc.get("currentStatus"))
                            && "50".equals(mdc.get("retryDelayMillis"))
                            && "TaskAssignWorker".equals(mdc.get("source")));
        }
    }

    @Test
    void nonReadyTaskIsSkippedAndNotCounted() throws InterruptedException {
        CountDownLatch processedLatch = new CountDownLatch(2);
        worker.addAssignmentQueueListener(new TaskAssignmentQueueListener() {
            @Override public void onTaskAssignmentProcessed(Task t) { processedLatch.countDown(); }
            @Override public void onAssignmentQueueDrained() {}
        });

        Task newTask = new Task();
        newTask.setTid("skipped");
        newTask.setStatus(TaskStatus.NEW);

        assertEquals(TaskAssignWorker.SubmitResult.ACCEPTED, worker.submitDetailed(newTask));
        assertEquals(TaskAssignWorker.SubmitResult.ACCEPTED, worker.submitDetailed(readyTask("processed")));

        assertTrue(processedLatch.await(3, TimeUnit.SECONDS));
        assertEquals(1, assigned.size());
        assertEquals("processed", assigned.get(0).getTid());
    }

    @Test
    void duplicateSubmitForSameTaskIsSkippedWhileAlreadyTracked() throws InterruptedException {
        worker.stop();

        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch assignedLatch = new CountDownLatch(1);
        CountDownLatch firstAttemptEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstAttempt = new CountDownLatch(1);
        TaskWorkerAssignListener slowListener = mock(TaskWorkerAssignListener.class);
        doAnswer(invocation -> {
            Task task = invocation.getArgument(0);
            attempts.incrementAndGet();
            firstAttemptEntered.countDown();
            assertTrue(releaseFirstAttempt.await(3, TimeUnit.SECONDS));
            task.transitionTo(TaskStatus.RUNNING);
            assignedLatch.countDown();
            return true;
        }).when(slowListener).onTaskAssign(any());

        worker = new TaskAssignWorker(slowListener, 50L);
        worker.start();

        Task task = readyTask("dedup");
        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertEquals(TaskAssignWorker.SubmitResult.ACCEPTED, worker.submitDetailed(task));
            assertTrue(firstAttemptEntered.await(3, TimeUnit.SECONDS), "first assignment attempt should start");
            assertEquals(TaskAssignWorker.SubmitResult.DEDUP_SKIPPED, worker.submitDetailed(task));
            releaseFirstAttempt.countDown();

            assertTrue(assignedLatch.await(3, TimeUnit.SECONDS), "Task should still be processed once");
            assertEquals(1, attempts.get());
            capture.assertHasEvent("ASSIGNMENT_QUEUE_SNAPSHOT", mdc ->
                    "dedup".equals(mdc.get("taskId"))
                            && "DEDUP_SKIPPED".equals(mdc.get("queueAction"))
                            && "SKIPPED".equals(mdc.get("result")));
        }
    }

    @Test
    void submitDefersDistinctTaskWhenAssignmentSignalQueueIsFull() throws InterruptedException {
        worker.stop();

        CountDownLatch firstAttemptStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstAttempt = new CountDownLatch(1);
        CountDownLatch rejectedEventuallyProcessed = new CountDownLatch(1);
        TaskWorkerAssignListener blockingListener = mock(TaskWorkerAssignListener.class);
        doAnswer(invocation -> {
            Task task = invocation.getArgument(0);
            firstAttemptStarted.countDown();
            assertTrue(releaseFirstAttempt.await(3, TimeUnit.SECONDS));
            task.transitionTo(TaskStatus.RUNNING);
            if ("rejected".equals(task.getTid())) {
                rejectedEventuallyProcessed.countDown();
            }
            return true;
        }).when(blockingListener).onTaskAssign(any());

        worker = new TaskAssignWorker(blockingListener, 50L, 1);
        worker.start();

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertEquals(TaskAssignWorker.SubmitResult.ACCEPTED, worker.submitDetailed(readyTask("blocking")));
            assertTrue(firstAttemptStarted.await(3, TimeUnit.SECONDS));
            assertEquals(TaskAssignWorker.SubmitResult.ACCEPTED, worker.submitDetailed(readyTask("queued")));
            assertEquals(TaskAssignWorker.SubmitResult.RETRY_SCHEDULED, worker.submitDetailed(readyTask("rejected")));

            capture.assertHasEvent("ASSIGNMENT_QUEUE_SNAPSHOT", mdc ->
                    "rejected".equals(mdc.get("taskId"))
                            && "QUEUE_FULL".equals(mdc.get("queueAction"))
                            && "DEFERRED".equals(mdc.get("result")));
            releaseFirstAttempt.countDown();
            assertTrue(rejectedEventuallyProcessed.await(3, TimeUnit.SECONDS),
                    "queue-full submission should be retried until it enters the assignment queue");
            capture.assertHasEvent("ASSIGNMENT_QUEUE_SNAPSHOT", mdc ->
                    "rejected".equals(mdc.get("taskId"))
                            && "SUBMIT_RETRY_SCHEDULED".equals(mdc.get("queueAction"))
                            && "DEFERRED".equals(mdc.get("result")));
            assertTrue(awaitCondition(() -> hasQueueSnapshotEvent(capture, "rejected", "RETRY_ENQUEUED",
                            mdc -> "SUCCESS".equals(mdc.get("result")))),
                    "retry-enqueued queue snapshot should be captured before assertions run");
        } finally {
            releaseFirstAttempt.countDown();
        }
    }

    @Test
    void runningTaskDeferredRequeueIsEnqueuedAfterCurrentAssignmentCycleFinishes() throws InterruptedException {
        worker.stop();

        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch secondAttemptLatch = new CountDownLatch(1);
        CountDownLatch deferredSubmitObserved = new CountDownLatch(1);
        CountDownLatch releaseFirstAttempt = new CountDownLatch(1);
        TaskWorkerAssignListener listener = mock(TaskWorkerAssignListener.class);
        doAnswer(invocation -> {
            Task task = invocation.getArgument(0);
            int currentAttempt = attempts.incrementAndGet();
            if (currentAttempt == 1) {
                assertEquals(TaskAssignWorker.SubmitResult.DEFERRED_REQUEUE_MARKED, worker.submitDetailed(task),
                        "second submit while tracked should be deferred");
                deferredSubmitObserved.countDown();
                assertTrue(releaseFirstAttempt.await(3, TimeUnit.SECONDS));
            } else if (currentAttempt == 2) {
                secondAttemptLatch.countDown();
            }
            return true;
        }).when(listener).onTaskAssign(any());

        worker = new TaskAssignWorker(listener, 50L);
        worker.start();

        Task task = runningTask("deferred-requeue");
        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertEquals(TaskAssignWorker.SubmitResult.ACCEPTED, worker.submitDetailed(task));
            assertTrue(deferredSubmitObserved.await(3, TimeUnit.SECONDS),
                    "deferred submit should be observed during the first assignment cycle");
            releaseFirstAttempt.countDown();

            assertTrue(secondAttemptLatch.await(3, TimeUnit.SECONDS),
                    "deferred requeue should trigger a second assignment cycle");
            assertEquals(2, attempts.get());
            capture.assertHasEvent("ASSIGNMENT_QUEUE_SNAPSHOT", mdc ->
                    "deferred-requeue".equals(mdc.get("taskId"))
                            && "REQUEUE_MARKED".equals(mdc.get("queueAction"))
                            && "DEFERRED".equals(mdc.get("result")));
            capture.assertHasEvent("ASSIGNMENT_QUEUE_SNAPSHOT", mdc ->
                    "deferred-requeue".equals(mdc.get("taskId"))
                            && "REQUEUE_ENQUEUED".equals(mdc.get("queueAction"))
                            && "SUCCESS".equals(mdc.get("result")));
        }
    }

    @Test
    void retryDelayUsesResolvedWorkloadPolicy() throws InterruptedException {
        worker.stop();

        TaskWorkerAssignListener retryingListener = mock(TaskWorkerAssignListener.class);
        when(retryingListener.onTaskAssign(any())).thenReturn(false);

        worker = new TaskAssignWorker(
                retryingListener,
                200L,
                10,
                new TaskRuntimeRetryPolicyResolver(25L, 100L, 0L, new TaskRuntimeProfileResolver())
        );
        worker.start();

        Task interactiveTask = interactiveTask("interactive-retry-delay");
        Task bulkTask = bulkTask("bulk-retry-delay");

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertEquals(TaskAssignWorker.SubmitResult.ACCEPTED, worker.submitDetailed(interactiveTask));
            assertEquals(TaskAssignWorker.SubmitResult.ACCEPTED, worker.submitDetailed(bulkTask));

            assertTrue(awaitCondition(() -> hasRetryDelayEvent(capture, "interactive-retry-delay", "25")),
                    "interactive retry should use the shorter resolved delay");
            assertTrue(awaitCondition(() -> hasRetryDelayEvent(capture, "bulk-retry-delay", "200")),
                    "bulk retry should keep the default delay");
        }
    }

    @Test
    void interactiveLaneContinuesWhileBulkLaneIsBlocked() throws InterruptedException {
        worker.stop();

        CountDownLatch bulkStarted = new CountDownLatch(1);
        CountDownLatch releaseBulk = new CountDownLatch(1);
        CountDownLatch interactiveProcessed = new CountDownLatch(1);

        TaskWorkerAssignListener laneAwareListener = mock(TaskWorkerAssignListener.class);
        doAnswer(invocation -> {
            Task task = invocation.getArgument(0);
            if ("bulk-blocked".equals(task.getTid())) {
                bulkStarted.countDown();
                assertTrue(releaseBulk.await(3, TimeUnit.SECONDS));
            }
            task.transitionTo(TaskStatus.RUNNING);
            if ("interactive-fast".equals(task.getTid())) {
                interactiveProcessed.countDown();
            }
            return true;
        }).when(laneAwareListener).onTaskAssign(any());

        worker = new TaskAssignWorker(laneAwareListener, 50L, 1);
        worker.start();

        Task bulkTask = bulkTask("bulk-blocked");
        Task interactiveTask = interactiveTask("interactive-fast");

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertEquals(TaskAssignWorker.SubmitResult.ACCEPTED, worker.submitDetailed(bulkTask));
            assertTrue(bulkStarted.await(3, TimeUnit.SECONDS), "bulk lane should start processing first");
            assertEquals(TaskAssignWorker.SubmitResult.ACCEPTED, worker.submitDetailed(interactiveTask));
            assertTrue(interactiveProcessed.await(1, TimeUnit.SECONDS),
                    "interactive lane should still make progress while bulk lane is blocked");

            capture.assertHasEvent("ASSIGNMENT_QUEUE_SNAPSHOT", mdc ->
                    "interactive-fast".equals(mdc.get("taskId"))
                            && "INTERACTIVE".equals(mdc.get("dispatchLane"))
                            && "SUBMITTED".equals(mdc.get("queueAction")));
            capture.assertHasEvent("ASSIGNMENT_QUEUE_SNAPSHOT", mdc ->
                    "bulk-blocked".equals(mdc.get("taskId"))
                            && "BULK".equals(mdc.get("dispatchLane"))
                            && "SUBMITTED".equals(mdc.get("queueAction")));
        } finally {
            releaseBulk.countDown();
        }
    }

    @Test
    void submitAllDrainsEvenWhenOneTaskIsSkippedAsNonDispatchable() throws InterruptedException {
        CountDownLatch allDoneLatch = new CountDownLatch(1);
        worker.addAssignmentQueueListener(new TaskAssignmentQueueListener() {
            @Override public void onTaskAssignmentProcessed(Task t) {}
            @Override public void onAssignmentQueueDrained() { allDoneLatch.countDown(); }
        });

        Task newTask = new Task();
        newTask.setTid("skipped-batch");
        newTask.setStatus(TaskStatus.NEW);

        worker.submitAll(List.of(newTask, readyTask("processed-batch")));

        assertTrue(allDoneLatch.await(5, TimeUnit.SECONDS),
                "submitAll should still drain when one task is skipped");
        assertEquals(1, assigned.size());
        assertEquals("processed-batch", assigned.get(0).getTid());
    }

    @Test
    void listenerReceivesQueueDrainedAfterLastSubmittedTask() throws InterruptedException {
        CountDownLatch allDoneLatch = new CountDownLatch(1);
        AtomicInteger completedCount = new AtomicInteger(0);
        worker.addAssignmentQueueListener(new TaskAssignmentQueueListener() {
            @Override public void onTaskAssignmentProcessed(Task t) { completedCount.incrementAndGet(); }
            @Override public void onAssignmentQueueDrained() { allDoneLatch.countDown(); }
        });

        worker.submitAll(List.of(readyTask("a"), readyTask("b")));

        assertTrue(allDoneLatch.await(5, TimeUnit.SECONDS), "onAssignmentQueueDrained should fire");
        assertEquals(2, completedCount.get());
    }

    @Test
    void stopTerminatesWorkerCleanly() {
        worker.stop();
        assertTrue(true, "stop() returned without timeout");
        worker = new TaskAssignWorker(mock(TaskWorkerAssignListener.class));
        worker.start();
    }

    @Test
    void multipleListenersAddedConcurrentlyDoNotCauseCME() throws InterruptedException {
        int threadCount = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                worker.addAssignmentQueueListener(new TaskAssignmentQueueListener() {
                    @Override public void onTaskAssignmentProcessed(Task t) {}
                    @Override public void onAssignmentQueueDrained() {}
                });
                done.countDown();
            }).start();
        }

        start.countDown();
        for (int i = 0; i < 5; i++) {
            worker.submit(readyTask("concurrent-" + i));
        }

        assertTrue(done.await(5, TimeUnit.SECONDS), "All listener-adder threads should finish");
    }

    private Task readyTask(String tid) {
        Task t = new Task();
        t.setTid(tid);
        t.setStatus(TaskStatus.READY);
        return t;
    }

    private Task interactiveTask(String tid) {
        Task t = readyTask(tid);
        t.getExecutionSpec().setWorkloadClass(TaskWorkloadClass.INTERACTIVE);
        return t;
    }

    private Task bulkTask(String tid) {
        Task t = readyTask(tid);
        t.getExecutionSpec().setWorkloadClass(TaskWorkloadClass.BULK);
        return t;
    }

    private Task runningTask(String tid) {
        Task t = new Task();
        t.setTid(tid);
        t.setStatus(TaskStatus.RUNNING);
        return t;
    }

    private boolean awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private boolean hasRetryDelayEvent(TraceEventLogCapture capture, String taskId, String retryDelayMillis) {
        return capture.events("ASSIGNMENT_RETRY_SCHEDULED").stream()
                .map(event -> event.getMDCPropertyMap())
                .anyMatch(mdc -> taskId.equals(mdc.get("taskId"))
                        && retryDelayMillis.equals(mdc.get("retryDelayMillis"))
                        && "TaskAssignWorker".equals(mdc.get("source")));
    }

    private boolean hasQueueSnapshotEvent(TraceEventLogCapture capture,
                                          String taskId,
                                          String queueAction,
                                          Predicate<java.util.Map<String, String>> extraMatch) {
        return capture.events("ASSIGNMENT_QUEUE_SNAPSHOT").stream()
                .map(event -> event.getMDCPropertyMap())
                .anyMatch(mdc -> taskId.equals(mdc.get("taskId"))
                        && queueAction.equals(mdc.get("queueAction"))
                        && extraMatch.test(mdc));
    }
}
