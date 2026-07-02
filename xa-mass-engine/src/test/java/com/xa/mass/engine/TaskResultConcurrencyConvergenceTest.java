package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.engine.policy.ContractAwareTaskTerminalPolicy;
import com.xa.mass.task.runtime.ActiveTaskWorkSnapshot;
import com.xa.mass.task.runtime.AppendBatchOutcome;
import com.xa.mass.task.runtime.BacklogFrameV1;
import com.xa.mass.task.runtime.ClaimReadyOutcome;
import com.xa.mass.task.runtime.ClaimedWorkItem;
import com.xa.mass.task.runtime.DiscardTaskRuntimeOutcome;
import com.xa.mass.task.runtime.DiscardTaskWorkOutcome;
import com.xa.mass.task.runtime.FinalResultReadRequest;
import com.xa.mass.task.runtime.FinalResultRow;
import com.xa.mass.task.runtime.FinalResultWindow;
import com.xa.mass.task.runtime.LeaseRepairBatch;
import com.xa.mass.task.runtime.MessageFinalityOutcome;
import com.xa.mass.task.runtime.ResultCorrelationSnapshot;
import com.xa.mass.task.runtime.RetryPromotionBatch;
import com.xa.mass.task.runtime.RuntimeEpoch;
import com.xa.mass.task.runtime.RuntimeResultFact;
import com.xa.mass.task.runtime.ScoreCandidate;
import com.xa.mass.task.runtime.ScoreCandidateBatch;
import com.xa.mass.task.runtime.TaskCloseAttemptOutcome;
import com.xa.mass.task.runtime.TaskRuntimeConvergencePort;
import com.xa.mass.task.runtime.TaskRuntimeMetaV1;
import com.xa.mass.task.runtime.TaskRuntimeProgressSnapshot;
import com.xa.mass.task.runtime.TaskRuntimeReadPort;
import com.xa.mass.task.runtime.TaskRuntimeResultWindowReadModel;
import com.xa.mass.task.runtime.TaskRuntimeScorePort;
import com.xa.mass.task.runtime.TaskRuntimeWorkPort;
import com.xa.mass.task.runtime.TaskScoreV1;
import com.xa.mass.task.runtime.WorkerReservationEvidence;
import com.xa.mass.task.runtime.memory.InMemoryTaskRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskResultConcurrencyConvergenceTest {

    private TaskManager taskManager;
    private TaskRuntimeServingLane taskRuntimeServingLane;

    @BeforeEach
    void setUp() {
        Harness harness = servingLaneTaskManager(new InMemoryTaskRuntime());
        taskManager = harness.manager();
        taskRuntimeServingLane = harness.lane();
    }

    @AfterEach
    void tearDown() {
        if (taskManager != null) {
            taskManager.shutdown();
        }
    }

    @Test
    void concurrentDuplicateSuccessCallbacksCloseAttemptOnlyOnce() throws Exception {
        Task task = createRunningTask(taskManager, "concurrent-duplicate-success", 1, 3);
        ClaimedWorkItem claimed = claimSingle(taskManager, task.getTid(), "worker-duplicate", "batch-duplicate");

        EventCounts counts = registerCounts(taskManager, task.getTid());
        Map<String, Object> firstOutput = Map.of("winner", "first");
        Map<String, Object> secondOutput = Map.of("winner", "second");

        List<Boolean> accepted = runConcurrently(
                () -> taskRuntimeServingLane.ingestTaskResult(task.getTid(), claimed.messageId(), true, "done-first", null, firstOutput),
                () -> taskRuntimeServingLane.ingestTaskResult(task.getTid(), claimed.messageId(), true, "done-second", null, secondOutput)
        );

        Task finalTask = taskManager.getTask(task.getTid());
        var row = taskRuntimeServingLane.readTaskResults(task.getTid(), 0, 10).rows().getFirst();

        assertEquals(List.of(true, true), accepted);
        assertEquals(TaskStatus.TERMINAL, finalTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, finalTask.getTerminalReason());
        assertEquals(1, finalTask.getTaskSuccessNumber());
        assertEquals(1, taskManager.getTaskRuntimeProgressSnapshot(task.getTid()).finalCount());
        assertEquals(1, taskRuntimeServingLane.countVisibleTaskResults(task.getTid()));
        assertTrue(row.success());
        assertTrue(firstOutput.equals(row.resultPayloadJson()) || secondOutput.equals(row.resultPayloadJson()));
        assertEquals(1, counts.attemptClosed().get());
        assertEquals(1, counts.logicallyFinal().get());
        assertEquals(1, counts.terminal().get());
    }

    @Test
    void concurrentSuccessCallbackAndExpiryProduceSingleOutcome() throws Exception {
        Task task = createRunningTask(taskManager, "concurrent-success-expire", 1, 0);
        ClaimedWorkItem claimed = claimSingle(taskManager, task.getTid(), "worker-expiry", "batch-expiry");

        EventCounts counts = registerCounts(taskManager, task.getTid());

        runConcurrently(
                () -> taskRuntimeServingLane.ingestTaskResult(
                        task.getTid(),
                        claimed.messageId(),
                        true,
                        "done",
                        null,
                        Map.of("outcome", "success")
                ),
                () -> taskRuntimeServingLane.expireLeasedWork(task.getTid(), claimed.messageId())
        );

        Task currentTask = taskManager.getTask(task.getTid());
        TaskRuntimeProgressSnapshot stats = taskManager.getTaskRuntimeProgressSnapshot(task.getTid());
        long visibleResults = taskRuntimeServingLane.countVisibleTaskResults(task.getTid());

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
        ClaimedWorkItem claimed = claimSingle(taskManager, task.getTid(), "worker-retry", "batch-retry");

        EventCounts counts = registerCounts(taskManager, task.getTid());

        runConcurrently(
                () -> taskRuntimeServingLane.ingestTaskResult(
                        task.getTid(),
                        claimed.messageId(),
                        false,
                        "boom-once",
                        "SYNTHETIC_RETRY",
                        Map.of("outcome", "retry")
                ),
                () -> taskRuntimeServingLane.ingestTaskResult(
                        task.getTid(),
                        claimed.messageId(),
                        true,
                        "done",
                        null,
                        Map.of("outcome", "success")
                )
        );

        Task currentTask = taskManager.getTask(task.getTid());
        TaskRuntimeProgressSnapshot stats = taskManager.getTaskRuntimeProgressSnapshot(task.getTid());
        long visibleResults = taskRuntimeServingLane.countVisibleTaskResults(task.getTid());

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
    }

    @Test
    void successCallbacksForDifferentMessagesDoNotSerializeBeforeTaskRuntimeResultPort() throws Exception {
        BlockingResultTaskRuntime blockingRuntime = new BlockingResultTaskRuntime(2);
        Harness concurrentHarness = servingLaneTaskManager(blockingRuntime);
        TaskManager concurrentTaskManager = concurrentHarness.manager();
        TaskRuntimeServingLane concurrentLane = concurrentHarness.lane();

        try {
            Task task = createRunningTask(concurrentTaskManager, "concurrent-different-messages", 2, 3);
            List<ClaimedWorkItem> claimed = claimSequentially(concurrentLane, concurrentTaskManager, task.getTid(), 2);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                Future<Boolean> firstFuture = executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return concurrentLane.ingestTaskResult(
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
                    return concurrentLane.ingestTaskResult(
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
                    "different messages in one task should reach task-runtime result apply concurrently");

            Task finalTask = concurrentTaskManager.getTask(task.getTid());
            assertEquals(TaskStatus.TERMINAL, finalTask.getStatus());
            assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, finalTask.getTerminalReason());
            assertEquals(2, finalTask.getTaskSuccessNumber());
            assertEquals(2, concurrentLane.countVisibleTaskResults(task.getTid()));
        } finally {
            concurrentTaskManager.shutdown();
        }
    }

    private static Harness servingLaneTaskManager(InMemoryTaskRuntime runtime) {
        return servingLaneTaskManager(
                runtime,
                runtime,
                runtime,
                runtime,
                runtime);
    }

    private static Harness servingLaneTaskManager(TaskRuntimePorts runtime) {
        return servingLaneTaskManager(
                runtime,
                runtime,
                runtime,
                runtime,
                runtime);
    }

    private static Harness servingLaneTaskManager(TaskRuntimeWorkPort workPort,
                                                  TaskRuntimeScorePort scorePort,
                                                  TaskRuntimeConvergencePort convergencePort,
                                                  TaskRuntimeReadPort readPort,
                                                  TaskRuntimeResultWindowReadModel resultWindowReadModel) {
        InMemoryTaskShellRuntimeStore storage = new InMemoryTaskShellRuntimeStore();
        TaskManager manager = new TaskManager(
                storage,
                storage,
                new ContractAwareTaskTerminalPolicy(),
                null);
        var commands = new TaskCommandService(manager);
        var queries = new TaskQueryService(manager);
        var events = new TaskEventService(manager);
        var lane = new TaskRuntimeServingLane(
                workPort,
                scorePort,
                convergencePort,
                readPort,
                resultWindowReadModel,
                queries,
                commands,
                events,
                new ContractAwareTaskTerminalPolicy(),
                null,
                TraceEventLogger.noop(),
                300L,
                TaskManager.MAX_INGEST_BATCH_ITEMS,
                86_400_000L);
        manager.installTaskRuntimeServingLane(lane);
        return new Harness(manager, lane);
    }

    private static Task createRunningTask(TaskManager manager,
                                          String taskName,
                                          int messageCount,
                                          int defaultMaxRetryCount) {
        TaskShellCreateRequestDto request = new TaskShellCreateRequestDto();
        request.setSourceRef(taskName);
        request.setProject("demoApp");
        request.setContract(TaskContract.BATCH);
        request.setSharedConfig(Map.of(TaskSharedConfig.WORKER_GROUP_ID, "group-1"));
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

    private ClaimedWorkItem claimSingle(TaskManager manager,
                                               String taskId,
                                               String workerId,
                                               String batchId) {
        return TaskRuntimeClaimTestSupport.claimSingle(
                taskRuntimeServingLane,
                manager.getWorkLeaseSeconds(),
                taskId,
                "group-1",
                workerId,
                batchId);
    }

    private static List<ClaimedWorkItem> claimSequentially(TaskRuntimeServingLane lane,
                                                           TaskManager manager,
                                                           String taskId,
                                                           int count) {
        List<ClaimedWorkItem> claimed = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            claimed.add(TaskRuntimeClaimTestSupport.claimSingle(
                    lane,
                    manager.getWorkLeaseSeconds(),
                    taskId,
                    "group-1",
                    "worker-" + index,
                    "batch-" + index));
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
    private static List<Boolean> runConcurrently(Callable<Boolean>... operations) throws Exception {
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
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private record Harness(TaskManager manager, TaskRuntimeServingLane lane) {
    }

    private record EventCounts(AtomicInteger attemptClosed,
                               AtomicInteger logicallyFinal,
                               AtomicInteger terminal) {
    }

    private interface TaskRuntimePorts extends TaskRuntimeWorkPort,
            TaskRuntimeScorePort,
            TaskRuntimeConvergencePort,
            TaskRuntimeReadPort,
            TaskRuntimeResultWindowReadModel {
    }

    private static final class BlockingResultTaskRuntime implements TaskRuntimePorts {
        private final InMemoryTaskRuntime delegate = new InMemoryTaskRuntime();
        private final CountDownLatch enteredApplyResultLatch;
        private final CountDownLatch releaseApplyResultLatch = new CountDownLatch(1);
        private final AtomicInteger concurrentApplyResult = new AtomicInteger();
        private final AtomicLong maxConcurrentApplyResult = new AtomicLong();

        private BlockingResultTaskRuntime(int expectedConcurrentApplyResults) {
            this.enteredApplyResultLatch = new CountDownLatch(expectedConcurrentApplyResults);
        }

        @Override
        public AppendBatchOutcome appendBacklog(String taskId, List<BacklogFrameV1> frames, int maxBatchSize) {
            return delegate.appendBacklog(taskId, frames, maxBatchSize);
        }

        @Override
        public ClaimReadyOutcome claimBacklog(ScoreCandidate candidate,
                                              List<WorkerReservationEvidence> reservations,
                                              int maxItems,
                                              long leaseMillis,
                                              long nowMillis) {
            return delegate.claimBacklog(candidate, reservations, maxItems, leaseMillis, nowMillis);
        }

        @Override
        public void putRuntimeMeta(TaskRuntimeMetaV1 meta) {
            delegate.putRuntimeMeta(meta);
        }

        @Override
        public void setTaskScore(String taskId, String laneKey, RuntimeEpoch epoch, TaskScoreV1 score) {
            delegate.setTaskScore(taskId, laneKey, epoch, score);
        }

        @Override
        public void removeTaskScore(String taskId, String laneKey, RuntimeEpoch epoch) {
            delegate.removeTaskScore(taskId, laneKey, epoch);
        }

        @Override
        public Optional<ScoreCandidate> scoreCandidate(String taskId, String laneKey) {
            return delegate.scoreCandidate(taskId, laneKey);
        }

        @Override
        public ScoreCandidateBatch discoverSchedulable(String laneKey, long maxScore, int limit) {
            return delegate.discoverSchedulable(laneKey, maxScore, limit);
        }

        @Override
        public RetryPromotionBatch promoteDueRetries(String laneKey, long nowMillis, int taskLimit, int itemLimit) {
            return delegate.promoteDueRetries(laneKey, nowMillis, taskLimit, itemLimit);
        }

        @Override
        public LeaseRepairBatch scanExpiredLeases(String laneKey, long nowMillis, int taskLimit, int itemLimit) {
            return delegate.scanExpiredLeases(laneKey, nowMillis, taskLimit, itemLimit);
        }

        @Override
        public MessageFinalityOutcome applyResult(RuntimeResultFact fact) {
            int current = concurrentApplyResult.incrementAndGet();
            maxConcurrentApplyResult.accumulateAndGet(current, Math::max);
            enteredApplyResultLatch.countDown();
            try {
                assertTrue(releaseApplyResultLatch.await(5, TimeUnit.SECONDS));
                return delegate.applyResult(fact);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } finally {
                concurrentApplyResult.decrementAndGet();
            }
        }

        @Override
        public TaskCloseAttemptOutcome closeIfDrained(String taskId, String laneKey, RuntimeEpoch epoch) {
            return delegate.closeIfDrained(taskId, laneKey, epoch);
        }

        @Override
        public DiscardTaskRuntimeOutcome discardRuntime(String taskId,
                                                        String laneKey,
                                                        RuntimeEpoch epoch,
                                                        String reason) {
            return delegate.discardRuntime(taskId, laneKey, epoch, reason);
        }

        @Override
        public DiscardTaskWorkOutcome discardWork(String taskId, RuntimeEpoch epoch, String reason) {
            return delegate.discardWork(taskId, epoch, reason);
        }

        @Override
        public ResultCorrelationSnapshot resultCorrelation(String taskId, String messageId) {
            return delegate.resultCorrelation(taskId, messageId);
        }

        @Override
        public ActiveTaskWorkSnapshot activeWorkForTask(String taskId, int limit) {
            return delegate.activeWorkForTask(taskId, limit);
        }

        @Override
        public TaskRuntimeProgressSnapshot progressSnapshot(String taskId) {
            return delegate.progressSnapshot(taskId);
        }

        @Override
        public FinalResultWindow readFinalResults(FinalResultReadRequest request) {
            return delegate.readFinalResults(request);
        }

        @Override
        public Optional<FinalResultRow> getFinalResultByMessageId(String taskId, String messageId) {
            return delegate.getFinalResultByMessageId(taskId, messageId);
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
}
