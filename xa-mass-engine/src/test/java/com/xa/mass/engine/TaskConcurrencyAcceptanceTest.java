package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.engine.policy.AllWorkFinalTaskTerminalPolicy;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionStatus;
import com.xa.mass.storage.api.projection.TaskMessageProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageProjectionStatus;
import com.xa.mass.storage.memory.InMemoryTaskStorage;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskConcurrencyAcceptanceTest {

    private RecordingTaskScheduler scheduler;
    private ProjectionAwareTaskManager taskManager;

    @BeforeEach
    void setUp() {
        scheduler = new RecordingTaskScheduler();
        InMemoryTaskStorage taskStorage = new InMemoryTaskStorage();
        taskManager = new ProjectionAwareTaskManager(scheduler, taskStorage, taskStorage, new InMemoryTaskWorkRuntime());
    }

    @Test
    void concurrentDuplicateSuccessCallbacksCloseAttemptOnlyOnce() throws Exception {
        Task task = createRunningSingleMessageTask("concurrent-duplicate-success");
        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message);

        AtomicInteger attemptClosedCount = new AtomicInteger();
        AtomicInteger logicallyFinalCount = new AtomicInteger();
        AtomicInteger terminalCount = new AtomicInteger();
        registerCounts(task.getTid(), attemptClosedCount, logicallyFinalCount, terminalCount);

        Map<String, Object> firstOutput = Map.of("winner", "first");
        Map<String, Object> secondOutput = Map.of("winner", "second");

        runConcurrently(
                () -> taskManager.ingestTaskResult(task.getTid(), message.messageId(), true, "done-first", null, firstOutput),
                () -> taskManager.ingestTaskResult(task.getTid(), message.messageId(), true, "done-second", null, secondOutput)
        );

        Task finalTask = taskManager.getTask(task.getTid());
        TaskDetailStore.TaskMessageProjection finalMessage = awaitVisibleTaskMessageProjection(
                task.getTid(),
                message.messageId(),
                TaskMessageProjectionStatus.SUCCESS
        );
        TaskDetailStore.TaskMessageAttemptProjection finalAttempt = CompatibilityProjectionAwait.awaitVisibleTaskMessageAttemptProjection(
                taskManager,
                task.getTid(),
                message.messageId(),
                TaskMessageAttemptProjectionStatus.SUCCEEDED
        );

        assertEquals(TaskStatus.TERMINAL, finalTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, finalTask.getTerminalReason());
        assertEquals(1, finalTask.getTaskSuccessNumber());

        assertEquals(TaskMessageProjectionStatus.SUCCESS, finalMessage.status());
        assertEquals(TaskMessageProjectionFinalReason.BUSINESS_SUCCESS, finalMessage.finalReason());
        assertTrue(firstOutput.equals(finalMessage.output()) || secondOutput.equals(finalMessage.output()));

        assertNotNull(finalAttempt);
        assertTrue(finalAttempt.status().isFinal());
        assertEquals(1, attemptClosedCount.get());
        assertEquals(1, logicallyFinalCount.get());
        assertEquals(1, terminalCount.get());
    }

    @Test
    void concurrentSuccessCallbackAndExpiryProduceSingleFinalOutcome() throws Exception {
        Task task = createRunningSingleMessageTask("concurrent-success-expire");
        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignRunningMessage(task, message);

        AtomicInteger attemptClosedCount = new AtomicInteger();
        AtomicInteger logicallyFinalCount = new AtomicInteger();
        AtomicInteger terminalCount = new AtomicInteger();
        registerCounts(task.getTid(), attemptClosedCount, logicallyFinalCount, terminalCount);

        runConcurrently(
                () -> taskManager.ingestTaskResult(
                        task.getTid(),
                        message.messageId(),
                        true,
                        "done",
                        null,
                        Map.of("outcome", "success")
                ),
                () -> taskManager.expireLeasedWork(task.getTid(), message.messageId())
        );

        Task finalTask = taskManager.getTask(task.getTid());
        TaskDetailStore.TaskMessageProjection finalMessage =
                CompatibilityProjectionAwait.awaitVisibleTaskMessageProjectionToLeaveStatus(
                        taskManager,
                        task.getTid(),
                        message.messageId(),
                        TaskMessageProjectionStatus.ASSIGNED
                );
        TaskDetailStore.TaskMessageAttemptProjection finalAttempt =
                CompatibilityProjectionAwait.awaitVisibleTaskMessageAttemptProjectionToBecomeFinal(
                        taskManager,
                        task.getTid(),
                        message.messageId()
                );

        assertEquals(1, attemptClosedCount.get());
        assertNotNull(finalAttempt);
        assertTrue(finalAttempt.status().isFinal());

        if (finalMessage.status() == TaskMessageProjectionStatus.SUCCESS) {
            assertEquals(1, logicallyFinalCount.get());
            assertEquals(1, terminalCount.get());
            assertEquals(TaskMessageProjectionFinalReason.BUSINESS_SUCCESS, finalMessage.finalReason());
            assertEquals(TaskStatus.TERMINAL, finalTask.getStatus());
            assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, finalTask.getTerminalReason());
            assertEquals(1, finalTask.getTaskSuccessNumber());
        } else if (finalMessage.status() == TaskMessageProjectionStatus.FAILED) {
            assertEquals(1, logicallyFinalCount.get());
            assertEquals(1, terminalCount.get());
            assertEquals(TaskMessageProjectionFinalReason.RETRY_EXHAUSTED, finalMessage.finalReason());
            assertEquals(TaskStatus.TERMINAL, finalTask.getStatus());
            assertEquals(TaskTerminalReason.ALL_MESSAGES_FAILED, finalTask.getTerminalReason());
            assertEquals(0, finalTask.getTaskSuccessNumber());
        } else {
            assertEquals(TaskMessageProjectionStatus.INIT, finalMessage.status());
            assertEquals(0, logicallyFinalCount.get());
            assertEquals(0, terminalCount.get());
            assertNull(finalMessage.finalReason());
            assertFalse(finalTask.getStatus().isFinal());
            assertEquals(0, finalTask.getTaskSuccessNumber());
        }
    }

    @Test
    void concurrentRetryableFailureAndSuccessDoNotDoubleFinalize() throws Exception {
        Task task = createRunningSingleMessageTask("concurrent-retry-success", 1);
        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
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
                () -> taskManager.ingestTaskResult(
                        task.getTid(),
                        message.messageId(),
                        false,
                        "boom-once",
                        "SYNTHETIC_RETRY",
                        Map.of("outcome", "retry")
                ),
                () -> taskManager.ingestTaskResult(
                        task.getTid(),
                        message.messageId(),
                        true,
                        "done",
                        null,
                        Map.of("outcome", "success")
                )
        );

        Task currentTask = taskManager.getTask(task.getTid());
        TaskDetailStore.TaskMessageProjection currentMessage =
                CompatibilityProjectionAwait.awaitVisibleTaskMessageProjectionToLeaveStatus(
                        taskManager,
                        task.getTid(),
                        message.messageId(),
                        TaskMessageProjectionStatus.ASSIGNED
                );
        TaskDetailStore.TaskMessageAttemptProjection latestAttempt =
                CompatibilityProjectionAwait.awaitVisibleTaskMessageAttemptProjectionToBecomeFinal(
                        taskManager,
                        task.getTid(),
                        message.messageId()
                );

        assertEquals(1, attemptClosedCount.get());
        assertNotNull(latestAttempt);
        assertTrue(latestAttempt.status().isFinal());

        if (currentMessage.status() == TaskMessageProjectionStatus.SUCCESS) {
            assertEquals(TaskMessageProjectionFinalReason.BUSINESS_SUCCESS, currentMessage.finalReason());
            assertEquals(TaskStatus.TERMINAL, currentTask.getStatus());
            assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, currentTask.getTerminalReason());
            assertEquals(1, currentTask.getTaskSuccessNumber());
            assertEquals(1, logicallyFinalCount.get());
            assertEquals(1, terminalCount.get());
            assertEquals(0, dispatchRequestedCount.get());
        } else {
            assertEquals(TaskMessageProjectionStatus.INIT, currentMessage.status());
            assertEquals(1, currentMessage.retryCount());
            assertNull(currentMessage.finalReason());
            assertNull(currentMessage.errorMessage());
            assertNull(currentMessage.latestAttemptWorkerId());
            assertNull(currentMessage.latestAttemptWorkerContextId());
            assertNull(currentMessage.latestAttemptBatchId());
            assertEquals(TaskStatus.RUNNING, currentTask.getStatus());
            assertEquals(0, currentTask.getTaskSuccessNumber());
            assertEquals(0, logicallyFinalCount.get());
            assertEquals(0, terminalCount.get());
            assertEquals(0, dispatchRequestedCount.get());
            assertNull(taskManager.getLatestActiveAttemptProjectionRecord(task.getTid(), message.messageId()));
        }
    }

    @Test
    void callbackAcceptedTraceCarriesAttemptBatchId() {
        Task task = createRunningSingleMessageTask("callback-accepted-batch-id");
        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(taskManager.ingestTaskResult(
                    task.getTid(),
                    message.messageId(),
                    true,
                    "done",
                    null,
                    Map.of("outcome", "success")
            ));
            capture.assertHasEvent("CALLBACK_ACCEPTED", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.messageId().equals(mdc.get("messageId"))
                            && "worker-".concat(message.messageId()).equals(mdc.get("workerId"))
                            && "batch-0".equals(mdc.get("latestAttemptBatchId")));
        }
    }

    @Test
    void successCallbacksForDifferentMessagesDoNotSerializeAtTaskLevel() throws Exception {
        RecordingTaskScheduler localScheduler = new RecordingTaskScheduler();
        BlockingApplyResultRuntime blockingRuntime = new BlockingApplyResultRuntime(2);
        InMemoryTaskStorage concurrentTaskStorage = new InMemoryTaskStorage();
        ProjectionAwareTaskManager concurrentTaskManager = new ProjectionAwareTaskManager(
                localScheduler,
                concurrentTaskStorage,
                concurrentTaskStorage,
                new AllWorkFinalTaskTerminalPolicy(),
                blockingRuntime
        );

        Task task = createRunningTask(concurrentTaskManager, "concurrent-different-messages", 2, 3);
        List<TaskDetailStore.TaskMessageProjection> messages = concurrentTaskManager.getTaskMessageRecords(task.getTid());
        TaskDetailStore.TaskMessageProjection first = assignMessage(concurrentTaskManager, task, messages.get(0));
        TaskDetailStore.TaskMessageProjection second = assignMessage(concurrentTaskManager, task, messages.get(1));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> firstFuture = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return concurrentTaskManager.ingestTaskResult(
                        task.getTid(),
                        first.messageId(),
                        true,
                        "done-first",
                        null,
                        Map.of("message", "first")
                );
            });
            Future<Boolean> secondFuture = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return concurrentTaskManager.ingestTaskResult(
                        task.getTid(),
                        second.messageId(),
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
        TaskDetailStore.TaskMessageProjection message = countingTaskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(countingTaskManager, task, message);

        assertTrue(countingTaskManager.ingestTaskResult(
                task.getTid(),
                message.messageId(),
                true,
                "done",
                null,
                Map.of("outcome", "success")
        ));
        assertEquals(1, countingTaskManager.progressUpdateCount(task.getTid()));

        assertTrue(countingTaskManager.ingestTaskResult(
                task.getTid(),
                message.messageId(),
                true,
                "done-duplicate",
                null,
                Map.of("outcome", "duplicate")
        ));
        assertEquals(1, countingTaskManager.progressUpdateCount(task.getTid()));
    }

    @Test
    void resultIngestRecoversCompatibilityProjectionFromRuntimeLeaseWhenMissing() {
        FirstLookupMissingTaskStorage taskStorage = new FirstLookupMissingTaskStorage();
        ProjectionAwareTaskManager recoveringTaskManager = new ProjectionAwareTaskManager(
                new RecordingTaskScheduler(),
                taskStorage,
                taskStorage,
                new InMemoryTaskWorkRuntime()
        );

        Task task = createRunningTask(recoveringTaskManager, "recover-projection-from-runtime", 1, 3);
        TaskDetailStore.TaskMessageProjection message = recoveringTaskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(recoveringTaskManager, task, message);
        taskStorage.suppressNextTaskMessageLookup(task.getTid(), message.messageId());

        assertTrue(recoveringTaskManager.ingestTaskResult(
                task.getTid(),
                message.messageId(),
                true,
                "done",
                null,
                Map.of("outcome", "success")
        ));
        taskStorage.clearSuppressedTaskMessageLookup(task.getTid(), message.messageId());

        TaskDetailStore.TaskMessageProjection finalMessage = CompatibilityProjectionAwait.awaitVisibleTaskMessageProjection(
                recoveringTaskManager,
                task.getTid(),
                message.messageId(),
                TaskMessageProjectionStatus.SUCCESS
        );
        assertNotNull(finalMessage);
        assertEquals(TaskMessageProjectionStatus.SUCCESS, finalMessage.status());
        assertEquals(TaskMessageProjectionFinalReason.BUSINESS_SUCCESS, finalMessage.finalReason());
        assertEquals(Map.of("outcome", "success"), finalMessage.output());
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
        List<TaskDetailStore.TaskMessageProjection> messages = coalescingTaskManager.getTaskMessageRecords(task.getTid());
        for (TaskDetailStore.TaskMessageProjection message : messages) {
            assignMessage(coalescingTaskManager, task, message);
        }

        ExecutorService executor = Executors.newFixedThreadPool(messageCount);
        CountDownLatch ready = new CountDownLatch(messageCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            @SuppressWarnings("unchecked")
            Future<Boolean>[] futures = new Future[messageCount];
            for (int i = 0; i < messageCount; i++) {
                TaskDetailStore.TaskMessageProjection message = messages.get(i);
                futures[i] = executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return coalescingTaskManager.ingestTaskResult(
                            task.getTid(),
                            message.messageId(),
                            true,
                            "done-" + message.messageId(),
                            null,
                            Map.of("messageId", message.messageId())
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
        int progressResolveCount = coalescingTaskManager.progressResolveCount();
        assertTrue(progressResolveCount >= 2 && progressResolveCount <= 3,
                "burst progress convergence should remain bounded even when late requests land between coalesced passes");

        Task finalTask = coalescingTaskManager.getTask(task.getTid());
        assertEquals(TaskStatus.TERMINAL, finalTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, finalTask.getTerminalReason());
        assertEquals(messageCount, finalTask.getTaskSuccessNumber());
    }

    private Task createRunningSingleMessageTask(String taskName) {
        return createRunningSingleMessageTask(taskName, 3);
    }

    private Task createRunningSingleMessageTask(String taskName, int defaultMaxRetryCount) {
        return createRunningTask(taskManager, taskName, 1, defaultMaxRetryCount);
    }

    private Task createRunningTask(ProjectionAwareTaskManager manager, String taskName, int messageCount, int defaultMaxRetryCount) {
        Task task = createTask(manager, buildRequestWithRetry(taskName, messageCount, defaultMaxRetryCount));
        assertTrue(manager.approveTask(task.getTid()));
        task.setStatus(TaskStatus.RUNNING);
        assertTrue(manager.updateTask(task));
        return task;
    }

    private TaskCreateSpec buildRequestWithRetry(String taskName, int defaultMaxRetryCount) {
        return buildRequestWithRetry(taskName, 1, defaultMaxRetryCount);
    }

    private TaskCreateSpec buildRequestWithRetry(String taskName, int messageCount, int defaultMaxRetryCount) {
        TaskCreateSpec dto = buildRequest(taskName, messageCount);
        dto.shell().setExecutionSpec(taskExecutionSpec(1, defaultMaxRetryCount));
        return dto;
    }

    private void registerCounts(String taskId,
                                AtomicInteger attemptClosedCount,
                                AtomicInteger logicallyFinalCount,
                                AtomicInteger terminalCount) {
        taskManager.events().addTaskWorkAttemptClosedListener((task, attempt) -> {
            if (taskId.equals(task.getTid())) {
                attemptClosedCount.incrementAndGet();
            }
        });
        taskManager.events().addTaskWorkLogicallyFinalListener((task, event) -> {
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

    private TaskCreateSpec buildRequest(String taskName) {
        return buildRequest(taskName, 1);
    }

    private TaskCreateSpec buildRequest(String taskName, int messageCount) {
        TaskShellCreateRequestDto shell = new TaskShellCreateRequestDto();
        shell.setSourceRef(taskName);
        shell.setProject("demoApp");
        shell.setSharedConfig(Map.of("textContent", "concurrency", "routingCode", "us"));
        shell.setUserId("agent");
        shell.setExecutionSpec(taskExecutionSpec(1, 3));
        List<Map<String, Object>> inputs = java.util.stream.IntStream.range(0, messageCount)
                .mapToObj(index -> Map.<String, Object>of("target", "alpha-" + index))
                .toList();
        return new TaskCreateSpec(shell, inputs);
    }

    private Task createTask(ProjectionAwareTaskManager manager, TaskCreateSpec request) {
        Task task = manager.createTaskShell(request.shell());
        if (!request.inputs().isEmpty()) {
            manager.appendTaskItems(task.getTid(), request.inputs());
        }
        assertTrue(manager.sealTask(task.getTid()));
        return manager.getTask(task.getTid());
    }

    private TaskDetailStore.TaskMessageProjection assignMessage(Task task,
                                                                TaskDetailStore.TaskMessageProjection message) {
        return assignMessage(taskManager, task, message);
    }

    private record TaskCreateSpec(TaskShellCreateRequestDto shell,
                                  List<Map<String, Object>> inputs) {
    }

    private TaskExecutionSpec taskExecutionSpec(int batchSize, int defaultMaxRetryCount) {
        TaskExecutionSpec spec = new TaskExecutionSpec();
        spec.setBatchSize(batchSize);
        spec.setDefaultMaxRetryCount(defaultMaxRetryCount);
        return spec;
    }

    private TaskDetailStore.TaskMessageProjection assignMessage(ProjectionAwareTaskManager manager,
                                                                Task task,
                                                                TaskDetailStore.TaskMessageProjection message) {
        String suffix = message.messageId();
        if (manager.getTaskWorkRuntime().getActiveLease(task.getTid(), message.messageId()).isEmpty()) {
            manager.getTaskWorkRuntime().claimReady(
                    task.getTid(),
                    List.of(new WorkerClaimTarget(
                            "worker-" + suffix,
                            "worker-context-" + suffix,
                            "batch-" + message.retryCount(),
                            1
                    )),
                    1,
                    manager.getWorkLeaseSeconds()
            );
        }
        int attemptNo = Math.max(1, message.retryCount() + 1);
        String attemptId = TaskWorkAttemptIdSupport.runtimeAttemptId(
                message.messageId(),
                attemptNo,
                "worker-" + suffix,
                "worker-context-" + suffix,
                "batch-" + message.retryCount()
        );
        TaskDetailStore.TaskMessageProjection assignedProjection = ProjectionTestSupport.markAssigned(
                message,
                attemptId,
                "worker-" + suffix,
                "worker-context-" + suffix,
                "batch-" + message.retryCount()
        );
        assertTrue(manager.upsertTaskMessageProjectionRecord(task.getTid(), assignedProjection));

        TaskDetailStore.TaskMessageAttemptProjection compatibilityAttempt = ProjectionTestSupport.attempt(
                attemptId,
                task.getTid(),
                assignedProjection.messageId(),
                attemptNo,
                assignedProjection.latestAttemptWorkerId(),
                assignedProjection.latestAttemptWorkerContextId(),
                assignedProjection.latestAttemptBatchId(),
                TaskMessageAttemptProjectionStatus.DISPATCHED
        );
        manager.upsertTaskMessageAttemptAuditProjectionRecord(
                task.getTid(),
                assignedProjection.messageId(),
                compatibilityAttempt
        );
        return assignedProjection;
    }

    private TaskDetailStore.TaskMessageProjection assignRunningMessage(Task task,
                                                                       TaskDetailStore.TaskMessageProjection message) {
        TaskDetailStore.TaskMessageProjection assigned = assignMessage(task, message);
        TaskDetailStore.TaskMessageProjection runningProjection =
                ProjectionTestSupport.markRunning(assigned);
        assertTrue(taskManager.upsertTaskMessageProjectionRecord(task.getTid(), runningProjection));
        TaskDetailStore.TaskMessageAttemptProjection activeAttemptRecord =
                taskManager.getLatestActiveAttemptProjectionRecord(task.getTid(), assigned.messageId());
        assertNotNull(activeAttemptRecord);
        TaskDetailStore.TaskMessageAttemptProjection activeAttempt =
                activeAttemptRecord.status() == TaskMessageAttemptProjectionStatus.RUNNING
                        ? activeAttemptRecord
                        : ProjectionTestSupport.withAttemptStatus(
                        activeAttemptRecord,
                        TaskMessageAttemptProjectionStatus.RUNNING,
                        activeAttemptRecord.finalReason(),
                        activeAttemptRecord.errorMessage(),
                        activeAttemptRecord.errorCode(),
                        activeAttemptRecord.output()
                );
        assertTrue(taskManager.upsertTaskMessageAttemptAuditProjectionRecord(
                task.getTid(),
                assigned.messageId(),
                activeAttempt
        ));
        return runningProjection;
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

    private TaskDetailStore.TaskMessageProjection awaitVisibleTaskMessageProjection(
            String taskId,
            String messageId,
            TaskMessageProjectionStatus expectedStatus) {
        return CompatibilityProjectionAwait.awaitVisibleTaskMessageProjection(taskManager, taskId, messageId, expectedStatus);
    }

    private static final class RecordingTaskScheduler implements TaskScheduler {
        @Override
        public TaskScheduler.SchedulingResult scheduleTask(Task task) {
            return TaskScheduler.SchedulingResult.success();
        }

        @Override
        public List<TaskScheduler.SchedulingResult> scheduleTasks(List<Task> tasks) {
            return List.of();
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

    private static final class CountingTaskManager extends ProjectionAwareTaskManager {
        private final ConcurrentHashMap<String, AtomicInteger> progressUpdateCounts = new ConcurrentHashMap<>();

        private CountingTaskManager(TaskScheduler taskScheduler, InMemoryTaskStorage taskStorage) {
            super(taskScheduler, taskStorage, taskStorage, new InMemoryTaskWorkRuntime());
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

    private static final class CoalescingCountingTaskManager extends ProjectionAwareTaskManager {
        private final AtomicInteger progressRequestCount = new AtomicInteger();
        private final AtomicInteger progressResolveCount = new AtomicInteger();
        private final CountDownLatch firstProgressResolveEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirstProgressResolve = new CountDownLatch(1);
        private final CountDownLatch allProgressRequestsReached;

        private CoalescingCountingTaskManager(TaskScheduler taskScheduler,
                                              InMemoryTaskStorage taskStorage,
                                              TaskWorkRuntime taskWorkRuntime,
                                              int expectedProgressRequests) {
            super(taskScheduler, taskStorage, taskStorage, new AllWorkFinalTaskTerminalPolicy(), taskWorkRuntime);
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

    private static final class FirstLookupMissingTaskStorage extends InMemoryTaskStorage {
        private final ConcurrentHashMap<String, AtomicInteger> suppressedReads = new ConcurrentHashMap<>();

        @Override
        public java.util.Optional<TaskDetailStore.TaskMessageProjection> getTaskMessageProjection(String taskId, String messageId) {
            AtomicInteger remaining = suppressedReads.get(taskId + "|" + messageId);
            if (remaining != null && remaining.getAndDecrement() > 0) {
                return java.util.Optional.empty();
            }
            return super.getTaskMessageProjection(taskId, messageId);
        }

        private void suppressNextTaskMessageLookup(String taskId, String messageId) {
            suppressedReads.put(taskId + "|" + messageId, new AtomicInteger(1));
        }

        private void clearSuppressedTaskMessageLookup(String taskId, String messageId) {
            suppressedReads.remove(taskId + "|" + messageId);
        }
    }
}
