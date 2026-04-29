package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.policy.AllWorkFinalTaskTerminalPolicy;
import com.xa.mass.engine.storage.InMemoryTaskStorage;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.ClaimedTaskWork;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import com.xa.mass.runtime.api.ResultApplyOutcome;
import com.xa.mass.runtime.api.TaskWorkClaimOptions;
import com.xa.mass.runtime.api.TaskWorkEnvelope;
import com.xa.mass.runtime.api.TaskWorkResult;
import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.runtime.api.TaskWorkRuntimeStats;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.runtime.api.WorkEnqueueOptions;
import com.xa.mass.runtime.api.WorkEnqueueOutcome;
import com.xa.mass.engine.util.TraceEventLogCapture;
import com.xa.mass.runtime.api.WorkerClaimTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
        taskManager = new TaskManager(scheduler, new InMemoryTaskStorage(), new InMemoryTaskWorkRuntime());
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
        Task task = createRunningSingleMessageTask("concurrent-retry-success", 1);
        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(task, message);

        AtomicInteger attemptClosedCount = new AtomicInteger();
        AtomicInteger logicallyFinalCount = new AtomicInteger();
        AtomicInteger terminalCount = new AtomicInteger();
        AtomicInteger dispatchRequestedCount = new AtomicInteger();
        registerCounts(task.getTid(), attemptClosedCount, logicallyFinalCount, terminalCount);
        taskManager.events().addTaskDispatchListener(t -> {
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

    @Test
    void successCallbacksForDifferentMessagesDoNotSerializeAtTaskLevel() throws Exception {
        RecordingTaskScheduler localScheduler = new RecordingTaskScheduler();
        BlockingApplyResultRuntime blockingRuntime = new BlockingApplyResultRuntime(2);
        TaskManager concurrentTaskManager = new TaskManager(
                localScheduler,
                new InMemoryTaskStorage(),
                new AllWorkFinalTaskTerminalPolicy(),
                blockingRuntime
        );

        Task task = createRunningTask(concurrentTaskManager, "concurrent-different-messages", 2, 3);
        List<TaskMsg> messages = concurrentTaskManager.getTaskMessages(task.getTid());
        TaskMsg first = assignMessage(concurrentTaskManager, task, messages.get(0));
        TaskMsg second = assignMessage(concurrentTaskManager, task, messages.get(1));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> firstFuture = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return concurrentTaskManager.handleTaskMessageResult(
                        task.getTid(),
                        first.getMessageId(),
                        true,
                        "done-first",
                        null,
                        Map.of("message", "first")
                );
            });
            Future<Boolean> secondFuture = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return concurrentTaskManager.handleTaskMessageResult(
                        task.getTid(),
                        second.getMessageId(),
                        true,
                        "done-second",
                        null,
                        Map.of("message", "second")
                );
            });

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(blockingRuntime.awaitApplyResultCalls(5, TimeUnit.SECONDS));
            blockingRuntime.releaseBlockedResults();

            assertTrue(firstFuture.get(10, TimeUnit.SECONDS));
            assertTrue(secondFuture.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertTrue(blockingRuntime.maxConcurrentApplyResult() >= 2,
                "different messages in one task should be able to apply results concurrently");

        Task finalTask = concurrentTaskManager.getTask(task.getTid());
        assertEquals(TaskStatus.TERMINAL, finalTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, finalTask.getTerminalReason());
        assertEquals(2, finalTask.getTaskSuccessNumber());
    }

    @Test
    void duplicateSuccessCallbackDoesNotTriggerExtraTaskProgressRecompute() {
        CountingTaskManager countingTaskManager = new CountingTaskManager(
                new RecordingTaskScheduler(),
                new InMemoryTaskStorage()
        );
        Task task = createRunningTask(countingTaskManager, "duplicate-progress-recompute", 1, 3);
        TaskMsg message = countingTaskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(countingTaskManager, task, message);

        assertTrue(countingTaskManager.handleTaskMessageResult(
                task.getTid(),
                message.getMessageId(),
                true,
                "done",
                null,
                Map.of("outcome", "success")
        ));
        assertEquals(1, countingTaskManager.progressUpdateCount(task.getTid()));

        assertTrue(countingTaskManager.handleTaskMessageResult(
                task.getTid(),
                message.getMessageId(),
                true,
                "done-duplicate",
                null,
                Map.of("outcome", "duplicate")
        ));
        assertEquals(1, countingTaskManager.progressUpdateCount(task.getTid()));
    }

    @Test
    void concurrentSuccessBurstCoalescesTaskProgressRecompute() throws Exception {
        int messageCount = 8;
        RecordingTaskScheduler localScheduler = new RecordingTaskScheduler();
        BlockingApplyResultRuntime blockingRuntime = new BlockingApplyResultRuntime(messageCount);
        CoalescingCountingTaskManager coalescingTaskManager = new CoalescingCountingTaskManager(
                localScheduler,
                new InMemoryTaskStorage(),
                blockingRuntime,
                messageCount
        );

        Task task = createRunningTask(coalescingTaskManager, "coalesced-progress-burst", messageCount, 3);
        List<TaskMsg> messages = coalescingTaskManager.getTaskMessages(task.getTid());
        for (TaskMsg message : messages) {
            assignMessage(coalescingTaskManager, task, message);
        }

        ExecutorService executor = Executors.newFixedThreadPool(messageCount);
        CountDownLatch ready = new CountDownLatch(messageCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            @SuppressWarnings("unchecked")
            Future<Boolean>[] futures = new Future[messageCount];
            for (int i = 0; i < messageCount; i++) {
                TaskMsg message = messages.get(i);
                futures[i] = executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return coalescingTaskManager.handleTaskMessageResult(
                            task.getTid(),
                            message.getMessageId(),
                            true,
                            "done-" + message.getMessageId(),
                            null,
                            Map.of("messageId", message.getMessageId())
                    );
                });
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(blockingRuntime.awaitApplyResultCalls(5, TimeUnit.SECONDS));
            blockingRuntime.releaseBlockedResults();
            assertTrue(coalescingTaskManager.awaitFirstProgressResolve(5, TimeUnit.SECONDS));
            assertTrue(coalescingTaskManager.awaitAllProgressRequests(5, TimeUnit.SECONDS));
            coalescingTaskManager.releaseFirstProgressResolve();

            for (Future<Boolean> future : futures) {
                assertTrue(future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertEquals(messageCount, coalescingTaskManager.progressRequestCount());
        assertEquals(2, coalescingTaskManager.progressResolveCount(),
                "burst progress convergence should collapse to a bounded number of task-level recomputes");

        Task finalTask = coalescingTaskManager.getTask(task.getTid());
        assertEquals(TaskStatus.TERMINAL, finalTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, finalTask.getTerminalReason());
        assertEquals(messageCount, finalTask.getTaskSuccessNumber());
    }

    private Task createRunningSingleMessageTask(String taskName) {
        return createRunningSingleMessageTask(taskName, 3);
    }

    private Task createRunningSingleMessageTask(String taskName, int defaultMsgMaxRetryCount) {
        return createRunningTask(taskManager, taskName, 1, defaultMsgMaxRetryCount);
    }

    private Task createRunningTask(TaskManager manager, String taskName, int messageCount, int defaultMsgMaxRetryCount) {
        Task task = manager.createTask(buildRequestWithRetry(taskName, messageCount, defaultMsgMaxRetryCount));
        assertTrue(manager.approveTask(task.getTid()));
        task.setStatus(TaskStatus.RUNNING);
        assertTrue(manager.updateTask(task));
        return task;
    }

    private TaskCreateRequestDto buildRequestWithRetry(String taskName, int defaultMsgMaxRetryCount) {
        TaskCreateRequestDto dto = buildRequestWithRetry(taskName, 1, defaultMsgMaxRetryCount);
        return dto;
    }

    private TaskCreateRequestDto buildRequestWithRetry(String taskName, int messageCount, int defaultMsgMaxRetryCount) {
        TaskCreateRequestDto dto = buildRequest(taskName, messageCount);
        dto.setDefaultMsgMaxRetryCount(defaultMsgMaxRetryCount);
        return dto;
    }

    private void registerCounts(String taskId,
                                AtomicInteger attemptClosedCount,
                                AtomicInteger logicallyFinalCount,
                                AtomicInteger terminalCount) {
        taskManager.events().addTaskMessageAttemptClosedListener((task, taskMsg, attempt) -> {
            if (taskId.equals(task.getTid())) {
                attemptClosedCount.incrementAndGet();
            }
        });
        taskManager.events().addTaskMessageLogicallyFinalListener((task, taskMsg) -> {
            if (taskId.equals(task.getTid())) {
                logicallyFinalCount.incrementAndGet();
            }
        });
        taskManager.events().addTaskTerminalListener(task -> {
            if (taskId.equals(task.getTid())) {
                terminalCount.incrementAndGet();
            }
        });
    }

    private TaskCreateRequestDto buildRequest(String taskName) {
        return buildRequest(taskName, 1);
    }

    private TaskCreateRequestDto buildRequest(String taskName, int messageCount) {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName(taskName);
        dto.setProject("demoApp");
        dto.setSharedConfig(Map.of("textContent", "concurrency", "routingCode", "us"));
        dto.setUserId("agent");
        dto.setInputs(java.util.stream.IntStream.range(0, messageCount)
                .mapToObj(index -> Map.<String, Object>of("target", "alpha-" + index))
                .toList());
        dto.setBatchSize(1);
        return dto;
    }

    private TaskMsg assignMessage(Task task, TaskMsg message) {
        return assignMessage(taskManager, task, message);
    }

    private TaskMsg assignMessage(TaskManager manager, Task task, TaskMsg message) {
        String suffix = message.getMessageId();
        if (manager.getTaskWorkRuntime().getActiveLease(task.getTid(), message.getMessageId()).isEmpty()) {
            manager.getTaskWorkRuntime().claimReady(
                    task.getTid(),
                    List.of(new WorkerClaimTarget(
                            "worker-" + suffix,
                            "worker-context-" + suffix,
                            "batch-" + message.getRetryCount(),
                            1
                    )),
                    1,
                    manager.getTaskMessageLeaseSeconds()
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
        assertTrue(manager.updateTaskMessage(task.getTid(), message));

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
        manager.addTaskMessageAttempt(task.getTid(), message.getMessageId(), attempt);
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

    private static final class BlockingApplyResultRuntime implements TaskWorkRuntime {
        private final InMemoryTaskWorkRuntime delegate = new InMemoryTaskWorkRuntime();
        private final CountDownLatch enteredApplyResultLatch;
        private final CountDownLatch releaseApplyResultLatch = new CountDownLatch(1);
        private final AtomicInteger concurrentApplyResult = new AtomicInteger();
        private final AtomicLong maxConcurrentApplyResult = new AtomicLong();

        private BlockingApplyResultRuntime(int expectedConcurrentApplyResults) {
            this.enteredApplyResultLatch = new CountDownLatch(expectedConcurrentApplyResults);
        }

        @Override
        public WorkEnqueueOutcome enqueue(TaskWorkEnvelope item, WorkEnqueueOptions options) {
            return delegate.enqueue(item, options);
        }

        @Override
        public List<String> readyTaskIds(int limit) {
            return delegate.readyTaskIds(limit);
        }

        @Override
        public List<ClaimedTaskWork> claimReady(String taskId,
                                                List<WorkerClaimTarget> workers,
                                                TaskWorkClaimOptions options) {
            return delegate.claimReady(taskId, workers, options);
        }

        @Override
        public ResultApplyOutcome applyResult(TaskWorkResult result) {
            int current = concurrentApplyResult.incrementAndGet();
            maxConcurrentApplyResult.accumulateAndGet(current, Math::max);
            enteredApplyResultLatch.countDown();
            try {
                assertTrue(releaseApplyResultLatch.await(5, TimeUnit.SECONDS));
                return delegate.applyResult(result);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } finally {
                concurrentApplyResult.decrementAndGet();
            }
        }

        @Override
        public List<ActiveLeaseRecord> pollExpiredLeases(int limit, Instant now) {
            return delegate.pollExpiredLeases(limit, now);
        }

        @Override
        public List<ActiveLeaseRecord> activeLeases(String taskId) {
            return delegate.activeLeases(taskId);
        }

        @Override
        public java.util.Optional<ActiveLeaseRecord> getActiveLease(String taskId, String messageId) {
            return delegate.getActiveLease(taskId, messageId);
        }

        @Override
        public boolean hasReadyWork(String taskId) {
            return delegate.hasReadyWork(taskId);
        }

        @Override
        public boolean hasActiveLeaseForWorker(String taskId, String workerId) {
            return delegate.hasActiveLeaseForWorker(taskId, workerId);
        }

        @Override
        public TaskWorkStats stats(String taskId) {
            return delegate.stats(taskId);
        }

        @Override
        public TaskWorkRuntimeStats stats() {
            return delegate.stats();
        }

        @Override
        public long discardTask(String taskId) {
            return delegate.discardTask(taskId);
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        private boolean awaitApplyResultCalls(long timeout, TimeUnit unit) throws InterruptedException {
            return enteredApplyResultLatch.await(timeout, unit);
        }

        private void releaseBlockedResults() {
            releaseApplyResultLatch.countDown();
        }

        private long maxConcurrentApplyResult() {
            return maxConcurrentApplyResult.get();
        }
    }

    private static final class CountingTaskManager extends TaskManager {
        private final ConcurrentHashMap<String, AtomicInteger> progressUpdateCounts = new ConcurrentHashMap<>();

        private CountingTaskManager(TaskScheduler taskScheduler, InMemoryTaskStorage taskStorage) {
            super(taskScheduler, taskStorage, new InMemoryTaskWorkRuntime());
        }

        @Override
        void updateTaskProgress(String taskId) {
            progressUpdateCounts.computeIfAbsent(taskId, ignored -> new AtomicInteger()).incrementAndGet();
            super.updateTaskProgress(taskId);
        }

        private int progressUpdateCount(String taskId) {
            AtomicInteger count = progressUpdateCounts.get(taskId);
            return count == null ? 0 : count.get();
        }
    }

    private static final class CoalescingCountingTaskManager extends TaskManager {
        private final AtomicInteger progressRequestCount = new AtomicInteger();
        private final AtomicInteger progressResolveCount = new AtomicInteger();
        private final CountDownLatch firstProgressResolveEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirstProgressResolve = new CountDownLatch(1);
        private final CountDownLatch allProgressRequestsReached;

        private CoalescingCountingTaskManager(TaskScheduler taskScheduler,
                                              InMemoryTaskStorage taskStorage,
                                              TaskWorkRuntime taskWorkRuntime,
                                              int expectedProgressRequests) {
            super(taskScheduler, taskStorage, new AllWorkFinalTaskTerminalPolicy(), taskWorkRuntime);
            this.allProgressRequestsReached = new CountDownLatch(expectedProgressRequests);
        }

        @Override
        void updateTaskProgress(String taskId) {
            progressRequestCount.incrementAndGet();
            allProgressRequestsReached.countDown();
            super.updateTaskProgress(taskId);
        }

        @Override
        void resolveTaskProgressUnderTaskLock(String taskId) {
            int current = progressResolveCount.incrementAndGet();
            if (current == 1) {
                firstProgressResolveEntered.countDown();
                try {
                    assertTrue(releaseFirstProgressResolve.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
            super.resolveTaskProgressUnderTaskLock(taskId);
        }

        private boolean awaitFirstProgressResolve(long timeout, TimeUnit unit) throws InterruptedException {
            return firstProgressResolveEntered.await(timeout, unit);
        }

        private boolean awaitAllProgressRequests(long timeout, TimeUnit unit) throws InterruptedException {
            return allProgressRequestsReached.await(timeout, unit);
        }

        private void releaseFirstProgressResolve() {
            releaseFirstProgressResolve.countDown();
        }

        private int progressRequestCount() {
            return progressRequestCount.get();
        }

        private int progressResolveCount() {
            return progressResolveCount.get();
        }
    }
}


