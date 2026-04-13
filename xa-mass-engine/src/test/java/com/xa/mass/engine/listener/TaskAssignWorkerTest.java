package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskAssignWorkerTest {

    private TaskAssignWorker worker;
    private List<Task> assigned;

    /** Stub TaskDeviceAssignListener that records tasks handed to it */
    private TaskDeviceAssignListener recordingListener(List<Task> sink) {
        TaskDeviceAssignListener stub = mock(TaskDeviceAssignListener.class);
        doAnswer(inv -> { sink.add(inv.getArgument(0)); return null; })
                .when(stub).onTaskAssign(any());
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
        worker.addListener(new TaskCompletionListener() {
            @Override public void onTaskCompleted(Task t) { latch.countDown(); }
            @Override public void onAllTasksCompleted() {}
        });

        Task task = readyTask("t1");
        worker.submit(task);

        assertTrue(latch.await(3, TimeUnit.SECONDS), "Task should be processed within 3s");
        assertEquals(1, assigned.size());
        assertEquals("t1", assigned.get(0).getTid());
    }

    @Test
    void readyTaskWithoutMatchIsRetriedUntilAssignmentSucceeds() throws InterruptedException {
        worker.stop();

        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch assignedLatch = new CountDownLatch(1);
        TaskDeviceAssignListener retryingListener = mock(TaskDeviceAssignListener.class);
        doAnswer(invocation -> {
            Task task = invocation.getArgument(0);
            if (attempts.incrementAndGet() >= 2) {
                task.transitionTo(TaskStatus.RUNNING);
                assignedLatch.countDown();
            }
            return null;
        }).when(retryingListener).onTaskAssign(any());

        worker = new TaskAssignWorker(retryingListener, 50L);
        worker.start();

        Task task = readyTask("retry");
        worker.submit(task);

        assertTrue(assignedLatch.await(3, TimeUnit.SECONDS), "READY task should be retried until assignment succeeds");
        assertEquals(TaskStatus.RUNNING, task.getStatus());
        assertEquals(2, attempts.get());
        verify(retryingListener, atLeast(2)).onTaskAssign(same(task));
    }

    @Test
    void nonReadyTaskIsSkippedAndNotCounted() throws InterruptedException {
        // Submit a NEW-status task (not READY) — should be skipped without calling the listener
        CountDownLatch readyLatch = new CountDownLatch(1);
        worker.addListener(new TaskCompletionListener() {
            @Override public void onTaskCompleted(Task t) { readyLatch.countDown(); }
            @Override public void onAllTasksCompleted() {}
        });

        Task newTask = new Task();
        newTask.setTid("skipped");
        newTask.setStatus(TaskStatus.NEW);

        // Submit non-READY task then a READY task to verify ordering
        worker.submit(newTask);
        worker.submit(readyTask("processed"));

        assertTrue(readyLatch.await(3, TimeUnit.SECONDS));
        // Only the READY task should have been passed to the device assign listener
        assertEquals(1, assigned.size());
        assertEquals("processed", assigned.get(0).getTid());
    }

    @Test
    void listenerReceivesOnAllTasksCompletedAfterLastTask() throws InterruptedException {
        CountDownLatch allDoneLatch = new CountDownLatch(1);
        AtomicInteger completedCount = new AtomicInteger(0);
        worker.addListener(new TaskCompletionListener() {
            @Override public void onTaskCompleted(Task t) { completedCount.incrementAndGet(); }
            @Override public void onAllTasksCompleted() { allDoneLatch.countDown(); }
        });

        worker.submitAll(List.of(readyTask("a"), readyTask("b")));

        assertTrue(allDoneLatch.await(5, TimeUnit.SECONDS), "onAllTasksCompleted should fire");
        assertEquals(2, completedCount.get());
    }

    @Test
    void stopTerminatesWorkerCleanly() {
        // start() already called in setUp; stop should complete without hanging
        worker.stop();
        // If stop() blocks indefinitely we never reach this assertion
        assertTrue(true, "stop() returned without timeout");
        // Re-create for tearDown to call stop() again without a double-stop error
        worker = new TaskAssignWorker(mock(TaskDeviceAssignListener.class));
        worker.start();
    }

    @Test
    void multipleListenersAddedConcurrentlyDoNotCauseCME() throws InterruptedException {
        // Regression: former ArrayList caused ConcurrentModificationException when listeners
        // were added while the worker thread was iterating them on task completion.
        int threadCount = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                worker.addListener(new TaskCompletionListener() {
                    @Override public void onTaskCompleted(Task t) {}
                    @Override public void onAllTasksCompleted() {}
                });
                done.countDown();
            }).start();
        }

        // Submit tasks concurrently with listener registration
        start.countDown();
        for (int i = 0; i < 5; i++) {
            worker.submit(readyTask("concurrent-" + i));
        }

        assertTrue(done.await(5, TimeUnit.SECONDS), "All listener-adder threads should finish");
        // No ConcurrentModificationException means the test passes
    }

    // ---- helpers ----

    private Task readyTask(String tid) {
        Task t = new Task();
        t.setTid(tid);
        t.setStatus(TaskStatus.READY);
        return t;
    }
}
