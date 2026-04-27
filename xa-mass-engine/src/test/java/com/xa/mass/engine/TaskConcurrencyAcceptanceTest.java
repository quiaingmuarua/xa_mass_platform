package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.storage.InMemoryTaskStorage;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.engine.util.TraceEventLogCapture;
import com.xa.mass.engine.work.WorkerClaimTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskConcurrencyAcceptanceTest {

    private RecordingTaskScheduler scheduler;
    private TaskManager taskManager;

    @BeforeEach
    void setUp() {
        scheduler = new RecordingTaskScheduler();
        taskManager = new TaskManager(scheduler, new InMemoryTaskStorage());
    }

    @Test
    void concurrentDuplicateSuccessCallbacksCloseAttemptOnlyOnce() throws Exception {
        Task task = createRunningSingleMessageTask("concurrent-duplicate-success");
        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(task, message);

        AtomicInteger attemptClosedCount = new AtomicInteger();
        AtomicInteger logicallyFinalCount = new AtomicInteger();
        AtomicInteger terminalCount = new AtomicInteger();
        registerCounts(task.getTid(), attemptClosedCount, logicallyFinalCount, terminalCount);

        Map<String, Object> firstOutput = Map.of("winner", "first");
        Map<String, Object> secondOutput = Map.of("winner", "second");

        runConcurrently(
                () -> taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), true, "done-first", null, firstOutput),
                () -> taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), true, "done-second", null, secondOutput)
        );

        Task finalTask = taskManager.getTask(task.getTid());
        TaskMsg finalMessage = taskManager.getTaskMessage(task.getTid(), message.getMessageId());
        TaskMsgAttempt finalAttempt = taskManager.getLatestTaskMessageAttempt(task.getTid(), message.getMessageId());

        assertEquals(TaskStatus.TERMINAL, finalTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, finalTask.getTerminalReason());
        assertEquals(1, finalTask.getTaskSuccessNumber());

        assertEquals(TaskMsgStatus.SUCCESS, finalMessage.getStatus());
        assertEquals(TaskMsgFinalReason.BUSINESS_SUCCESS, finalMessage.getFinalReason());
        assertTrue(firstOutput.equals(finalMessage.getOutput()) || secondOutput.equals(finalMessage.getOutput()));

        assertNotNull(finalAttempt);
        assertTrue(finalAttempt.isFinal());
        assertEquals(1, attemptClosedCount.get());
        assertEquals(1, logicallyFinalCount.get());
        assertEquals(1, terminalCount.get());
        assertEquals(1, scheduler.completedTaskMsgCount.get());
        assertEquals(0, scheduler.failedTaskMsgCount.get());
    }

    @Test
    void concurrentSuccessCallbackAndExpiryProduceSingleFinalOutcome() throws Exception {
        Task task = createRunningSingleMessageTask("concurrent-success-expire");
        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignRunningMessage(task, message);

        AtomicInteger attemptClosedCount = new AtomicInteger();
        AtomicInteger logicallyFinalCount = new AtomicInteger();
        AtomicInteger terminalCount = new AtomicInteger();
        registerCounts(task.getTid(), attemptClosedCount, logicallyFinalCount, terminalCount);

        runConcurrently(
                () -> taskManager.handleTaskMessageResult(
                        task.getTid(),
                        message.getMessageId(),
                        true,
                        "done",
                        null,
                        Map.of("outcome", "success")
                ),
                () -> taskManager.expireTaskMessage(task.getTid(), message.getMessageId())
        );

        Task finalTask = taskManager.getTask(task.getTid());
        TaskMsg finalMessage = taskManager.getTaskMessage(task.getTid(), message.getMessageId());
        TaskMsgAttempt finalAttempt = taskManager.getLatestTaskMessageAttempt(task.getTid(), message.getMessageId());

        assertEquals(1, attemptClosedCount.get());
        assertEquals(1, logicallyFinalCount.get());
        assertEquals(1, terminalCount.get());
        assertNotNull(finalAttempt);
        assertTrue(finalAttempt.isFinal());

        if (finalMessage.getStatus() == TaskMsgStatus.SUCCESS) {
            assertEquals(TaskMsgFinalReason.BUSINESS_SUCCESS, finalMessage.getFinalReason());
            assertEquals(TaskStatus.TERMINAL, finalTask.getStatus());
            assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, finalTask.getTerminalReason());
            assertEquals(1, finalTask.getTaskSuccessNumber());
            assertEquals(1, scheduler.completedTaskMsgCount.get());
            assertEquals(0, scheduler.failedTaskMsgCount.get());
        } else {
            assertEquals(TaskMsgStatus.EXPIRED, finalMessage.getStatus());
            assertEquals(TaskMsgFinalReason.LEASE_EXPIRED, finalMessage.getFinalReason());
            assertEquals(TaskStatus.TERMINAL, finalTask.getStatus());
            assertEquals(TaskTerminalReason.ALL_MESSAGES_FAILED, finalTask.getTerminalReason());
            assertEquals(0, finalTask.getTaskSuccessNumber());
            assertEquals(0, scheduler.completedTaskMsgCount.get());
            assertEquals(0, scheduler.failedTaskMsgCount.get());
        }
    }

    @Test
    void concurrentRetryableFailureAndSuccessDoNotDoubleFinalize() throws Exception {
        Task task = createRunningSingleMessageTask("concurrent-retry-success");
        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.setMaxRetryCount(1);
        taskManager.updateTaskMessage(task.getTid(), message);
        assignMessage(task, message);

        AtomicInteger attemptClosedCount = new AtomicInteger();
        AtomicInteger logicallyFinalCount = new AtomicInteger();
        AtomicInteger terminalCount = new AtomicInteger();
        AtomicInteger dispatchRequestedCount = new AtomicInteger();
        registerCounts(task.getTid(), attemptClosedCount, logicallyFinalCount, terminalCount);
        taskManager.addTaskDispatchListener(t -> {
            if (task.getTid().equals(t.getTid())) {
                dispatchRequestedCount.incrementAndGet();
            }
        });

        runConcurrently(
                () -> taskManager.handleTaskMessageResult(
                        task.getTid(),
                        message.getMessageId(),
                        false,
                        "boom-once",
                        "SYNTHETIC_RETRY",
                        Map.of("outcome", "retry")
                ),
                () -> taskManager.handleTaskMessageResult(
                        task.getTid(),
                        message.getMessageId(),
                        true,
                        "done",
                        null,
                        Map.of("outcome", "success")
                )
        );

        Task currentTask = taskManager.getTask(task.getTid());
        TaskMsg currentMessage = taskManager.getTaskMessage(task.getTid(), message.getMessageId());
        TaskMsgAttempt latestAttempt = taskManager.getLatestTaskMessageAttempt(task.getTid(), message.getMessageId());

        assertEquals(1, attemptClosedCount.get());
        assertNotNull(latestAttempt);
        assertTrue(latestAttempt.isFinal());

        if (currentMessage.getStatus() == TaskMsgStatus.SUCCESS) {
            assertEquals(TaskMsgFinalReason.BUSINESS_SUCCESS, currentMessage.getFinalReason());
            assertEquals(TaskStatus.TERMINAL, currentTask.getStatus());
            assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, currentTask.getTerminalReason());
            assertEquals(1, currentTask.getTaskSuccessNumber());
            assertEquals(1, logicallyFinalCount.get());
            assertEquals(1, terminalCount.get());
            assertEquals(0, dispatchRequestedCount.get());
            assertEquals(1, scheduler.completedTaskMsgCount.get());
            assertEquals(0, scheduler.failedTaskMsgCount.get());
        } else {
            assertEquals(TaskMsgStatus.INIT, currentMessage.getStatus());
            assertEquals(1, currentMessage.getRetryCount());
            assertNull(currentMessage.getFinalReason());
            assertNull(currentMessage.getErrorMessage());
            assertNull(currentMessage.getLatestAttemptWorkerId());
            assertNull(currentMessage.getLatestAttemptWorkerContextId());
            assertNull(currentMessage.getLatestAttemptBatchId());
            assertEquals(TaskStatus.RUNNING, currentTask.getStatus());
            assertEquals(0, currentTask.getTaskSuccessNumber());
            assertEquals(0, logicallyFinalCount.get());
            assertEquals(0, terminalCount.get());
            assertEquals(1, dispatchRequestedCount.get());
            assertEquals(0, scheduler.completedTaskMsgCount.get());
            assertEquals(0, scheduler.failedTaskMsgCount.get());
            assertNull(taskManager.getLatestActiveTaskMessageAttempt(task.getTid(), message.getMessageId()));
        }
    }

    @Test
    void callbackAcceptedTraceCarriesAttemptBatchId() {
        Task task = createRunningSingleMessageTask("callback-accepted-batch-id");
        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(task, message);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(taskManager.handleTaskMessageResult(
                    task.getTid(),
                    message.getMessageId(),
                    true,
                    "done",
                    null,
                    Map.of("outcome", "success")
            ));
            capture.assertHasEvent("CALLBACK_ACCEPTED", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.getMessageId().equals(mdc.get("messageId"))
                            && "worker-".concat(message.getMessageId()).equals(mdc.get("latestAttemptWorkerId"))
                            && "batch-0".equals(mdc.get("latestAttemptBatchId")));
        }
    }

    private Task createRunningSingleMessageTask(String taskName) {
        Task task = taskManager.createTask(buildRequest(taskName));
        assertTrue(taskManager.approveTask(task.getTid()));
        task.setStatus(TaskStatus.RUNNING);
        assertTrue(taskManager.updateTask(task));
        return task;
    }

    private void registerCounts(String taskId,
                                AtomicInteger attemptClosedCount,
                                AtomicInteger logicallyFinalCount,
                                AtomicInteger terminalCount) {
        taskManager.addTaskMessageAttemptClosedListener((task, taskMsg, attempt) -> {
            if (taskId.equals(task.getTid())) {
                attemptClosedCount.incrementAndGet();
            }
        });
        taskManager.addTaskMessageLogicallyFinalListener((task, taskMsg) -> {
            if (taskId.equals(task.getTid())) {
                logicallyFinalCount.incrementAndGet();
            }
        });
        taskManager.addTaskTerminalListener(task -> {
            if (taskId.equals(task.getTid())) {
                terminalCount.incrementAndGet();
            }
        });
    }

    private TaskCreateRequestDto buildRequest(String taskName) {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName(taskName);
        dto.setProject("demoApp");
        dto.setSharedConfig(Map.of("textContent", "concurrency", "routingCode", "us"));
        dto.setUserId("agent");
        dto.setInputs(List.of(Map.of("target", "alpha")));
        dto.setBatchSize(1);
        return dto;
    }

    private TaskMsg assignMessage(Task task, TaskMsg message) {
        String suffix = message.getMessageId();
        if (taskManager.getTaskWorkRuntime().getActiveLease(task.getTid(), message.getMessageId()).isEmpty()) {
            taskManager.getTaskWorkRuntime().claimReady(
                    task.getTid(),
                    List.of(new WorkerClaimTarget(
                            "worker-" + suffix,
                            "worker-context-" + suffix,
                            "batch-" + message.getRetryCount(),
                            1
                    )),
                    1,
                    taskManager.getTaskMessageLeaseSeconds()
            );
        }
        message.applyLatestAttemptProjection(
                "worker-" + suffix,
                "worker-context-" + suffix,
                "batch-" + message.getRetryCount()
        );
        if (message.getStatus() == TaskMsgStatus.INIT) {
            assertTrue(message.markAsAssigned());
        }
        assertTrue(taskManager.updateTaskMessage(task.getTid(), message));

        int attemptNo = message.getRetryCount() + 1;
        TaskMsgAttempt attempt = new TaskMsgAttempt(
                "attempt-" + message.getMessageId() + "-" + attemptNo,
                task.getTid(),
                message.getMessageId(),
                attemptNo
        );
        attempt.setWorkerId(message.getLatestAttemptWorkerId());
        attempt.setWorkerContextId(message.getLatestAttemptWorkerContextId());
        attempt.setBatchId(message.getLatestAttemptBatchId());
        assertTrue(attempt.markLeased(LocalDateTime.now().plusMinutes(5)));
        assertTrue(attempt.markDispatched());
        taskManager.addTaskMessageAttempt(task.getTid(), message.getMessageId(), attempt);
        return message;
    }

    private TaskMsg assignRunningMessage(Task task, TaskMsg message) {
        TaskMsg assigned = assignMessage(task, message);
        assertTrue(assigned.markAsRunning());
        assertTrue(taskManager.updateTaskMessage(task.getTid(), assigned));
        TaskMsgAttempt activeAttempt = taskManager.getLatestActiveTaskMessageAttempt(task.getTid(), assigned.getMessageId());
        assertNotNull(activeAttempt);
        assertTrue(activeAttempt.markRunning());
        assertTrue(taskManager.updateTaskMessageAttempt(task.getTid(), assigned.getMessageId(), activeAttempt));
        return assigned;
    }

    @SafeVarargs
    private final void runConcurrently(Callable<Boolean>... operations) throws Exception {
        CountDownLatch ready = new CountDownLatch(operations.length);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(operations.length);
        try {
            Future<Boolean>[] futures = new Future[operations.length];
            for (int i = 0; i < operations.length; i++) {
                Callable<Boolean> operation = operations[i];
                futures[i] = executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return operation.call();
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<Boolean> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static final class RecordingTaskScheduler implements TaskScheduler {
        private final AtomicInteger completedTaskMsgCount = new AtomicInteger();
        private final AtomicInteger failedTaskMsgCount = new AtomicInteger();

        @Override
        public TaskScheduler.SchedulingResult scheduleTask(Task task) {
            return TaskScheduler.SchedulingResult.success(List.of());
        }

        @Override
        public List<TaskScheduler.SchedulingResult> scheduleTasks(List<Task> tasks) {
            return List.of();
        }

        @Override
        public boolean handleTaskMsgCompletion(TaskMsg taskMsg) {
            completedTaskMsgCount.incrementAndGet();
            return true;
        }

        @Override
        public boolean handleTaskMsgFailure(TaskMsg taskMsg, String errorMessage) {
            failedTaskMsgCount.incrementAndGet();
            return true;
        }

        @Override
        public boolean retryTaskMsg(TaskMsg taskMsg) {
            return true;
        }

        @Override
        public boolean cancelTask(String taskId) {
            return true;
        }

        @Override
        public boolean pauseTask(String taskId) {
            return true;
        }

        @Override
        public boolean resumeTask(String taskId) {
            return true;
        }
    }
}
