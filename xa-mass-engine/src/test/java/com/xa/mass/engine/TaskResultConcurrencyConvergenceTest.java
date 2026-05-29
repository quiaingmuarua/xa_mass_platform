package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.ClaimedTaskWork;
import com.xa.mass.runtime.api.ResultApplyOutcome;
import com.xa.mass.runtime.api.TaskResultRuntimeRow;
import com.xa.mass.runtime.api.TaskWorkClaimOptions;
import com.xa.mass.runtime.api.TaskWorkEnvelope;
import com.xa.mass.runtime.api.TaskWorkResult;
import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.runtime.api.TaskWorkRuntimeStats;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.runtime.api.WorkEnqueueOptions;
import com.xa.mass.runtime.api.WorkEnqueueOutcome;
import com.xa.mass.runtime.api.WorkerClaimTarget;
import com.xa.mass.runtime.memory.InMemoryTaskResultRuntime;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import com.xa.mass.storage.memory.InMemoryTaskShellStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskResultConcurrencyConvergenceTest {

    private TaskManager taskManager;

    @BeforeEach
    void setUp() {
        InMemoryTaskShellStore storage = new InMemoryTaskShellStore();
        taskManager = new TaskManager(
                storage,
                new InMemoryTaskWorkRuntime(),
                new InMemoryTaskResultRuntime(),
                null
        );
    }

    @Test
    void concurrentDuplicateSuccessCallbacksCloseAttemptOnlyOnce() throws Exception {
        Task task = createRunningTask(taskManager, "concurrent-duplicate-success", 1, 3);
        ClaimedTaskWork claimed = claimSingle(taskManager, task.getTid(), "worker-duplicate", "batch-duplicate");

        EventCounts counts = registerCounts(taskManager, task.getTid());
        Map<String, Object> firstOutput = Map.of("winner", "first");
        Map<String, Object> secondOutput = Map.of("winner", "second");

        runConcurrently(
                () -> taskManager.ingestTaskResult(task.getTid(), claimed.messageId(), true, "done-first", null, firstOutput),
                () -> taskManager.ingestTaskResult(task.getTid(), claimed.messageId(), true, "done-second", null, secondOutput)
        );

        Task finalTask = taskManager.getTask(task.getTid());
        TaskResultRuntimeRow row = taskManager.getTaskResultRuntime()
                .getVisibleByMessageId(task.getTid(), claimed.messageId())
                .orElseThrow();

        assertEquals(TaskStatus.TERMINAL, finalTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, finalTask.getTerminalReason());
        assertEquals(1, finalTask.getTaskSuccessNumber());
        assertEquals(1, taskManager.getTaskWorkRuntime().stats(task.getTid()).finalCount());
        assertEquals(1, taskManager.getTaskResultRuntime().countVisibleResults(task.getTid()));
        assertEquals("SUCCESS", row.status());
        assertEquals("BUSINESS_SUCCESS", row.finalReason());
        assertTrue(firstOutput.equals(row.output()) || secondOutput.equals(row.output()));
        assertEquals(1, counts.attemptClosed().get());
        assertEquals(1, counts.logicallyFinal().get());
        assertEquals(1, counts.terminal().get());
    }

    @Test
    void concurrentSuccessCallbackAndExpiryProduceSingleOutcome() throws Exception {
        Task task = createRunningTask(taskManager, "concurrent-success-expire", 1, 0);
        ClaimedTaskWork claimed = claimSingle(taskManager, task.getTid(), "worker-expiry", "batch-expiry");

        EventCounts counts = registerCounts(taskManager, task.getTid());

        runConcurrently(
                () -> taskManager.ingestTaskResult(
                        task.getTid(),
                        claimed.messageId(),
                        true,
                        "done",
                        null,
                        Map.of("outcome", "success")
                ),
                () -> taskManager.expireLeasedWork(task.getTid(), claimed.messageId())
        );

        Task currentTask = taskManager.getTask(task.getTid());
        TaskWorkStats stats = taskManager.getTaskWorkRuntime().stats(task.getTid());
        long visibleResults = taskManager.getTaskResultRuntime().countVisibleResults(task.getTid());

        assertEquals(1, counts.attemptClosed().get());
        assertEquals(1, stats.finalCount());
        assertEquals(0, stats.readyCount());
        assertEquals(0, stats.processingCount());
        assertEquals(1, visibleResults);
        assertEquals(TaskStatus.TERMINAL, currentTask.getStatus());
        assertEquals(1, counts.logicallyFinal().get());
        assertEquals(1, counts.terminal().get());
        assertTrue(currentTask.getTerminalReason() == TaskTerminalReason.ALL_MESSAGES_SUCCEEDED
                || currentTask.getTerminalReason() == TaskTerminalReason.ALL_MESSAGES_FAILED);
    }

    @Test
    void concurrentRetryableFailureAndSuccessDoNotDoubleFinalize() throws Exception {
        Task task = createRunningTask(taskManager, "concurrent-retry-success", 1, 1);
        ClaimedTaskWork claimed = claimSingle(taskManager, task.getTid(), "worker-retry", "batch-retry");

        EventCounts counts = registerCounts(taskManager, task.getTid());
        AtomicInteger dispatchRequestedCount = new AtomicInteger();
        taskManager.events().addTaskDispatchListener(currentTask -> {
            if (task.getTid().equals(currentTask.getTid())) {
                dispatchRequestedCount.incrementAndGet();
            }
        });

        runConcurrently(
                () -> taskManager.ingestTaskResult(
                        task.getTid(),
                        claimed.messageId(),
                        false,
                        "boom-once",
                        "SYNTHETIC_RETRY",
                        Map.of("outcome", "retry")
                ),
                () -> taskManager.ingestTaskResult(
                        task.getTid(),
                        claimed.messageId(),
                        true,
                        "done",
                        null,
                        Map.of("outcome", "success")
                )
        );

        Task currentTask = taskManager.getTask(task.getTid());
        TaskWorkStats stats = taskManager.getTaskWorkRuntime().stats(task.getTid());
        long visibleResults = taskManager.getTaskResultRuntime().countVisibleResults(task.getTid());

        assertEquals(1, counts.attemptClosed().get());
        if (visibleResults == 1) {
            assertEquals(1, stats.finalCount());
            assertEquals(0, stats.readyCount());
            assertEquals(TaskStatus.TERMINAL, currentTask.getStatus());
            assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, currentTask.getTerminalReason());
            assertEquals(1, currentTask.getTaskSuccessNumber());
            assertEquals(1, counts.logicallyFinal().get());
            assertEquals(1, counts.terminal().get());
        } else {
            assertEquals(0, visibleResults);
            assertEquals(0, stats.finalCount());
            assertEquals(1, stats.readyCount());
            assertFalse(currentTask.getStatus().isFinal());
            assertEquals(0, currentTask.getTaskSuccessNumber());
            assertEquals(0, counts.logicallyFinal().get());
            assertEquals(0, counts.terminal().get());
        }
        assertEquals(0, dispatchRequestedCount.get());
    }

    @Test
    void successCallbacksForDifferentMessagesDoNotSerializeAtTaskLevel() throws Exception {
        BlockingApplyResultRuntime blockingRuntime = new BlockingApplyResultRuntime(2);
        TaskManager concurrentTaskManager = newManager(blockingRuntime);

        Task task = createRunningTask(concurrentTaskManager, "concurrent-different-messages", 2, 3);
        List<ClaimedTaskWork> claimed = claimSequentially(concurrentTaskManager, task.getTid(), 2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> firstFuture = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return concurrentTaskManager.ingestTaskResult(
                        task.getTid(),
                        claimed.get(0).messageId(),
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
                        claimed.get(1).messageId(),
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
        assertEquals(2, concurrentTaskManager.getTaskResultRuntime().countVisibleResults(task.getTid()));
    }

    @Test
    void duplicateSuccessCallbackDoesNotTriggerExtraTaskProgressRecompute() {
        CountingTaskManager countingTaskManager = new CountingTaskManager(new InMemoryTaskShellStore());
        Task task = createRunningTask(countingTaskManager, "duplicate-progress-recompute", 1, 3);
        ClaimedTaskWork claimed = claimSingle(countingTaskManager, task.getTid(), "worker-progress", "batch-progress");

        assertTrue(countingTaskManager.ingestTaskResult(
                task.getTid(),
                claimed.messageId(),
                true,
                "done",
                null,
                Map.of("outcome", "success")
        ));
        assertEquals(1, countingTaskManager.progressUpdateCount(task.getTid()));

        assertTrue(countingTaskManager.ingestTaskResult(
                task.getTid(),
                claimed.messageId(),
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
        BlockingApplyResultRuntime blockingRuntime = new BlockingApplyResultRuntime(messageCount);
        CoalescingCountingTaskManager coalescingTaskManager = new CoalescingCountingTaskManager(
                new InMemoryTaskShellStore(),
                blockingRuntime,
                messageCount
        );

        Task task = createRunningTask(coalescingTaskManager, "coalesced-progress-burst", messageCount, 3);
        List<ClaimedTaskWork> claimed = claimSequentially(coalescingTaskManager, task.getTid(), messageCount);

        ExecutorService executor = Executors.newFixedThreadPool(messageCount);
        CountDownLatch ready = new CountDownLatch(messageCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            @SuppressWarnings("unchecked")
            Future<Boolean>[] futures = new Future[messageCount];
            for (int index = 0; index < messageCount; index++) {
                ClaimedTaskWork work = claimed.get(index);
                futures[index] = executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return coalescingTaskManager.ingestTaskResult(
                            task.getTid(),
                            work.messageId(),
                            true,
                            "done-" + work.messageId(),
                            null,
                            Map.of("messageId", work.messageId())
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

    private static TaskManager newManager(TaskWorkRuntime taskWorkRuntime) {
        InMemoryTaskShellStore storage = new InMemoryTaskShellStore();
        return new TaskManager(storage, taskWorkRuntime, new InMemoryTaskResultRuntime(), null);
    }

    private static Task createRunningTask(TaskManager manager,
                                          String taskName,
                                          int messageCount,
                                          int defaultMaxRetryCount) {
        TaskShellCreateRequestDto request = new TaskShellCreateRequestDto();
        request.setSourceRef(taskName);
        request.setProject("demoApp");
        request.setSharedConfig(Map.of("textContent", "concurrency", "routingCode", "us"));
        request.setUserId("agent");
        request.setExecutionSpec(taskExecutionSpec(1, defaultMaxRetryCount));

        Task task = manager.createTaskShell(request);
        List<Map<String, Object>> inputs = java.util.stream.IntStream.range(0, messageCount)
                .mapToObj(index -> Map.<String, Object>of("target", "alpha-" + index))
                .toList();
        if (!inputs.isEmpty()) {
            manager.appendTaskItems(task.getTid(), inputs);
        }
        assertTrue(manager.sealTask(task.getTid()));
        assertTrue(manager.approveTask(task.getTid()));
        Task running = manager.getTask(task.getTid());
        running.setStatus(TaskStatus.RUNNING);
        assertTrue(manager.updateTask(running));
        return manager.getTask(task.getTid());
    }

    private static ClaimedTaskWork claimSingle(TaskManager manager,
                                               String taskId,
                                               String workerId,
                                               String batchId) {
        List<ClaimedTaskWork> claimed = manager.getTaskWorkRuntime().claimReady(
                taskId,
                List.of(WorkerClaimTarget.workerLevel(workerId, batchId, 1)),
                1,
                manager.getWorkLeaseSeconds()
        );
        assertEquals(1, claimed.size());
        return claimed.getFirst();
    }

    private static List<ClaimedTaskWork> claimSequentially(TaskManager manager,
                                                           String taskId,
                                                           int count) {
        List<ClaimedTaskWork> claimed = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            claimed.add(claimSingle(manager, taskId, "worker-" + index, "batch-" + index));
        }
        return claimed;
    }

    private static TaskExecutionSpec taskExecutionSpec(int batchSize, int defaultMaxRetryCount) {
        TaskExecutionSpec spec = new TaskExecutionSpec();
        spec.setBatchSize(batchSize);
        spec.setDefaultMaxRetryCount(defaultMaxRetryCount);
        return spec;
    }

    private static EventCounts registerCounts(TaskManager manager, String taskId) {
        EventCounts counts = new EventCounts(new AtomicInteger(), new AtomicInteger(), new AtomicInteger());
        manager.events().addTaskWorkAttemptClosedListener((task, attempt) -> {
            if (taskId.equals(task.getTid())) {
                counts.attemptClosed().incrementAndGet();
            }
        });
        manager.events().addTaskWorkLogicallyFinalListener((task, event) -> {
            if (taskId.equals(task.getTid())) {
                counts.logicallyFinal().incrementAndGet();
            }
        });
        manager.events().addTaskTerminalListener(task -> {
            if (taskId.equals(task.getTid())) {
                counts.terminal().incrementAndGet();
            }
        });
        return counts;
    }

    @SafeVarargs
    private static void runConcurrently(Callable<Boolean>... operations) throws Exception {
        CountDownLatch ready = new CountDownLatch(operations.length);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(operations.length);
        try {
            @SuppressWarnings("unchecked")
            Future<Boolean>[] futures = new Future[operations.length];
            for (int index = 0; index < operations.length; index++) {
                Callable<Boolean> operation = operations[index];
                futures[index] = executor.submit(() -> {
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

    private record EventCounts(AtomicInteger attemptClosed,
                               AtomicInteger logicallyFinal,
                               AtomicInteger terminal) {
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
        public Optional<ActiveLeaseRecord> getActiveLease(String taskId, String messageId) {
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

        private CountingTaskManager(InMemoryTaskShellStore taskStorage) {
            super(taskStorage, new InMemoryTaskWorkRuntime(), new InMemoryTaskResultRuntime(), null);
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

        private CoalescingCountingTaskManager(InMemoryTaskShellStore taskStorage,
                                              TaskWorkRuntime taskWorkRuntime,
                                              int expectedProgressRequests) {
            super(taskStorage, taskWorkRuntime, new InMemoryTaskResultRuntime(), null);
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
