package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.util.TraceEventLogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;

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
            worker.submit(task);

            assertTrue(latch.await(3, TimeUnit.SECONDS), "Task should be processed within 3s");
            assertEquals(1, assigned.size());
            assertEquals("t1", assigned.get(0).getTid());
            capture.assertHasEvent("ASSIGNMENT_QUEUE_SNAPSHOT", mdc ->
                    "t1".equals(mdc.get("taskId"))
                            && "SUBMITTED".equals(mdc.get("queueAction"))
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
            worker.submit(task);

            assertTrue(assignedLatch.await(3, TimeUnit.SECONDS), "READY task should be retried until assignment succeeds");
            assertEquals(TaskStatus.RUNNING, task.getStatus());
            assertEquals(2, attempts.get());
            verify(retryingListener, atLeast(2)).onTaskAssign(same(task));
            capture.assertHasEvent("ASSIGNMENT_RETRY_SCHEDULED", mdc ->
                    "retry".equals(mdc.get("taskId"))
                            && "READY".equals(mdc.get("currentStatus"))
                            && "50".equals(mdc.get("retryDelayMillis"))
                            && "TaskAssignWorker".equals(mdc.get("source")));
            capture.assertHasEvent("ASSIGNMENT_QUEUE_SNAPSHOT", mdc ->
                    "retry".equals(mdc.get("taskId"))
                            && "RETRY_SCHEDULED".equals(mdc.get("queueAction"))
                            && "1".equals(mdc.get("scheduledRetryCount")));
            capture.assertHasEvent("ASSIGNMENT_QUEUE_SNAPSHOT", mdc ->
                    "retry".equals(mdc.get("taskId"))
                            && "RETRY_ENQUEUED".equals(mdc.get("queueAction"))
                            && "TaskAssignWorker".equals(mdc.get("source")));
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
            worker.submit(task);

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

        worker.submit(newTask);
        worker.submit(readyTask("processed"));

        assertTrue(processedLatch.await(3, TimeUnit.SECONDS));
        assertEquals(1, assigned.size());
        assertEquals("processed", assigned.get(0).getTid());
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

    private Task runningTask(String tid) {
        Task t = new Task();
        t.setTid(tid);
        t.setStatus(TaskStatus.RUNNING);
        return t;
    }
}
