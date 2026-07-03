package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.runtime.api.BarrierClaim;
import com.xa.mass.runtime.api.BarrierMarkResult;
import com.xa.mass.runtime.api.ClaimedTaskWork;
import com.xa.mass.runtime.api.CommitResult;
import com.xa.mass.runtime.api.TaskResultCallbackDraft;
import com.xa.mass.runtime.api.TaskResultFinalDraft;
import com.xa.mass.runtime.api.TaskResultRepairCandidate;
import com.xa.mass.runtime.api.TaskResultRepairKind;
import com.xa.mass.runtime.api.TaskResultRuntime;
import com.xa.mass.runtime.api.TaskResultRuntimeRow;
import com.xa.mass.runtime.api.TaskResultWindow;
import com.xa.mass.runtime.api.WorkerClaimTarget;
import com.xa.mass.runtime.memory.InMemoryTaskResultRuntime;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskResultRuntimeConvergenceTest {

    private TaskManager taskManager;

    @BeforeEach
    void setUp() {
        InMemoryTaskShellRuntimeStore storage = new InMemoryTaskShellRuntimeStore();
        taskManager = new TaskManager(
                storage,
                new InMemoryTaskWorkRuntime(),
                new InMemoryTaskResultRuntime(),
                null
        );
    }

    @AfterEach
    void tearDown() {
        taskManager.shutdown();
    }

    @Test
    void visibleRuntimeResultCommitIsIdempotentForDuplicateCallbacks() {
        Task task = createRunningSingleItemTask(taskManager, "task-result-runtime-visible", 0);
        ClaimedTaskWork claimed = claimSingle(taskManager, task.getTid(), "worker-visible", "batch-visible");

        AtomicInteger logicalFinalEvents = new AtomicInteger();
        taskManager.events().addTaskWorkLogicallyFinalListener((currentTask, event) ->
                logicalFinalEvents.incrementAndGet());

        assertTrue(taskManager.ingestTaskResult(
                task.getTid(),
                claimed.messageId(),
                true,
                "done",
                null,
                Map.of("value", "ok")));

        TaskResultWindow window = taskManager.getTaskResultRuntime().readWindow(task.getTid(), 0, 10);
        assertEquals(1, window.items().size());
        TaskResultRuntimeRow row = window.items().getFirst();
        assertEquals(claimed.messageId(), row.messageId());
        assertEquals(1L, row.seq());
        assertEquals("SUCCESS", row.status());
        assertEquals("BUSINESS_SUCCESS", row.finalReason());
        assertEquals(Map.of("value", "ok"), row.output());
        assertTrue(row.logicalFinalPublished());
        assertTrue(row.progressApplied());
        assertEquals(1, logicalFinalEvents.get());

        assertTrue(taskManager.ingestTaskResult(task.getTid(), claimed.messageId(), false, "late-duplicate"));

        TaskResultWindow afterDuplicate = taskManager.getTaskResultRuntime().readWindow(task.getTid(), 0, 10);
        assertEquals(1, afterDuplicate.items().size());
        assertEquals(1, logicalFinalEvents.get());
    }

    @Test
    void retryableFailureDiscardsStageWithoutCreatingVisibleResultRow() {
        Task task = createRunningSingleItemTask(taskManager, "task-result-runtime-retry", 1);
        ClaimedTaskWork claimed = claimSingle(taskManager, task.getTid(), "worker-retry", "batch-retry");

        assertTrue(taskManager.ingestTaskResult(task.getTid(), claimed.messageId(), false, "boom-once", "BOOM"));

        assertEquals(0, taskManager.getTaskResultRuntime().countVisibleResults(task.getTid()));
        assertTrue(taskManager.getTaskResultRuntime().scanRepairCandidates(10).isEmpty());
    }

    @Test
    void visibleCommitFailureLeavesRepairCandidateUntilRepairPumpConverges() {
        String previousInterval = System.getProperty("xa.mass.engine.resultRepairPumpIntervalMillis");
        FlakyCommitTaskResultRuntime resultRuntime = new FlakyCommitTaskResultRuntime();
        TaskManager manager = null;
        try {
            System.setProperty("xa.mass.engine.resultRepairPumpIntervalMillis", "10");
            resultRuntime.blockRepairPumpScans();
            manager = newManager(resultRuntime);

            Task task = createRunningSingleItemTask(manager, "task-result-runtime-repair", 0);
            ClaimedTaskWork claimed = claimSingle(manager, task.getTid(), "worker-repair", "batch-repair");

            AtomicInteger logicalFinalEvents = new AtomicInteger();
            AtomicInteger attemptClosedEvents = new AtomicInteger();
            manager.events().addTaskWorkAttemptClosedListener((currentTask, attempt) ->
                    attemptClosedEvents.incrementAndGet());
            manager.events().addTaskWorkLogicallyFinalListener((currentTask, event) ->
                    logicalFinalEvents.incrementAndGet());

            resultRuntime.failNextVisibleCommit();
            assertTrue(manager.ingestTaskResult(task.getTid(), claimed.messageId(), true, "done"));

            assertEquals(0, manager.getTaskResultRuntime().countVisibleResults(task.getTid()));
            assertEquals(TaskStatus.RUNNING, manager.getTask(task.getTid()).getStatus());
            TaskManager capturedManager = manager;
            awaitCondition(
                    () -> !capturedManager.getTaskResultRuntime().scanRepairCandidates(10).isEmpty(),
                    "failed visible commit should leave a repair candidate before repair pump resumes");

            resultRuntime.allowRepairPumpScans();
            awaitCondition(
                    () -> capturedManager.getTaskResultRuntime().countVisibleResults(task.getTid()) == 1
                            && capturedManager.getTask(task.getTid()).getStatus() == TaskStatus.TERMINAL
                            && capturedManager.getTaskResultRuntime()
                            .getVisibleByMessageId(task.getTid(), claimed.messageId())
                            .filter(TaskResultRuntimeRow::progressApplied)
                            .isPresent(),
                    "repair pump should commit visible result and apply progress barrier");

            TaskResultRuntimeRow row = manager.getTaskResultRuntime()
                    .getVisibleByMessageId(task.getTid(), claimed.messageId())
                    .orElseThrow();
            assertEquals("SUCCESS", row.status());
            assertTrue(row.logicalFinalPublished());
            assertTrue(row.progressApplied());
            assertEquals(1, attemptClosedEvents.get());
            assertEquals(1, logicalFinalEvents.get());
            assertTrue(manager.getTaskResultRuntime().scanRepairCandidates(10).isEmpty());
        } finally {
            if (manager != null) {
                manager.shutdown();
            }
            restoreProperty("xa.mass.engine.resultRepairPumpIntervalMillis", previousInterval);
        }
    }

    @Test
    void duplicateCallbackAfterVisibleCommitFailureKeepsRepairCandidateUntilVisibleFinalConverges() {
        String previousInterval = System.getProperty("xa.mass.engine.resultRepairPumpIntervalMillis");
        FlakyCommitTaskResultRuntime resultRuntime = new FlakyCommitTaskResultRuntime();
        TaskManager manager = null;
        try {
            System.setProperty("xa.mass.engine.resultRepairPumpIntervalMillis", "10");
            resultRuntime.blockRepairPumpScans();
            manager = newManager(resultRuntime);

            Task task = createRunningSingleItemTask(manager, "task-result-runtime-repair-duplicate", 0);
            ClaimedTaskWork claimed = claimSingle(manager, task.getTid(), "worker-duplicate", "batch-duplicate");

            resultRuntime.failNextVisibleCommit();
            assertTrue(manager.ingestTaskResult(task.getTid(), claimed.messageId(), true, "done-first"));

            assertEquals(0, manager.getTaskResultRuntime().countVisibleResults(task.getTid()));
            assertEquals(TaskStatus.RUNNING, manager.getTask(task.getTid()).getStatus());
            TaskManager capturedManager = manager;
            awaitCondition(
                    () -> !capturedManager.getTaskResultRuntime().scanRepairCandidates(10).isEmpty(),
                    "failed visible commit should leave a repair candidate before duplicate callback replay");

            assertTrue(manager.ingestTaskResult(task.getTid(), claimed.messageId(), true, "done-duplicate"));
            assertEquals(0, manager.getTaskResultRuntime().countVisibleResults(task.getTid()));
            awaitCondition(
                    () -> !capturedManager.getTaskResultRuntime().scanRepairCandidates(10).isEmpty(),
                    "duplicate callback should not discard the staged repair breadcrumb before visible final exists");

            resultRuntime.allowRepairPumpScans();
            awaitCondition(
                    () -> capturedManager.getTaskResultRuntime().countVisibleResults(task.getTid()) == 1
                            && capturedManager.getTask(task.getTid()).getStatus() == TaskStatus.TERMINAL,
                    "repair pump should still converge after duplicate callback follows a failed visible commit");

            awaitCondition(
                    () -> capturedManager.getTaskResultRuntime().scanRepairCandidates(10).isEmpty(),
                    "repair candidate should drain once visible final, logical-final publish, and progress apply converge");
        } finally {
            if (manager != null) {
                manager.shutdown();
            }
            restoreProperty("xa.mass.engine.resultRepairPumpIntervalMillis", previousInterval);
        }
    }

    @Test
    void attemptClosedPublishFailureIsRepairedWithoutProjectionFallback() {
        String previousInterval = System.getProperty("xa.mass.engine.resultRepairPumpIntervalMillis");
        FlakyCommitTaskResultRuntime resultRuntime = new FlakyCommitTaskResultRuntime();
        TaskManager manager = null;
        try {
            System.setProperty("xa.mass.engine.resultRepairPumpIntervalMillis", "10");
            resultRuntime.blockRepairPumpScans();
            manager = newManager(resultRuntime);

            Task task = createRunningSingleItemTask(manager, "task-result-attempt-repair", 0);
            ClaimedTaskWork claimed = claimSingle(manager, task.getTid(), "worker-attempt", "batch-attempt");

            AtomicInteger attemptClosedEvents = new AtomicInteger();
            AtomicInteger logicalFinalEvents = new AtomicInteger();
            manager.events().addTaskWorkAttemptClosedListener((currentTask, attempt) ->
                    attemptClosedEvents.incrementAndGet());
            manager.events().addTaskWorkLogicallyFinalListener((currentTask, event) ->
                    logicalFinalEvents.incrementAndGet());

            resultRuntime.failNextAttemptClosedClaim();
            assertTrue(manager.ingestTaskResult(task.getTid(), claimed.messageId(), true, "done"));

            TaskResultRuntimeRow row = manager.getTaskResultRuntime()
                    .getVisibleByMessageId(task.getTid(), claimed.messageId())
                    .orElseThrow();
            assertFalse(row.attemptClosedPublished());
            assertTrue(row.logicalFinalPublished());
            assertTrue(row.progressApplied());
            assertEquals(0, attemptClosedEvents.get());
            assertEquals(1, logicalFinalEvents.get());
            assertTrue(manager.getTaskResultRuntime().scanRepairCandidates(10).stream()
                    .anyMatch(candidate -> candidate.kind() == TaskResultRepairKind.MISSING_ATTEMPT_CLOSED_PUBLISH));

            resultRuntime.allowRepairPumpScans();
            TaskManager capturedManager = manager;
            awaitCondition(
                    () -> capturedManager.getTaskResultRuntime()
                            .getVisibleByMessageId(task.getTid(), claimed.messageId())
                            .map(TaskResultRuntimeRow::attemptClosedPublished)
                            .orElse(false)
                            && attemptClosedEvents.get() == 1,
                    "repair pump should publish the missing attempt-closed event once");

            assertEquals(1, logicalFinalEvents.get());
            awaitCondition(
                    () -> capturedManager.getTaskResultRuntime().scanRepairCandidates(10).isEmpty(),
                    "fully converged result repair should clean staged callbacks");
            assertEquals(0, manager.getTaskResultRuntime().discardStagedCallbacksForMessage(task.getTid(), claimed.messageId()));
        } finally {
            if (manager != null) {
                manager.shutdown();
            }
            restoreProperty("xa.mass.engine.resultRepairPumpIntervalMillis", previousInterval);
        }
    }

    private static TaskManager newManager(TaskResultRuntime resultRuntime) {
        InMemoryTaskShellRuntimeStore storage = new InMemoryTaskShellRuntimeStore();
        return new TaskManager(storage, new InMemoryTaskWorkRuntime(), resultRuntime, null);
    }

    private static Task createRunningSingleItemTask(TaskManager manager,
                                                    String sourceRef,
                                                    int defaultMaxRetryCount) {
        TaskShellCreateRequestDto request = new TaskShellCreateRequestDto();
        request.setSourceRef(sourceRef);
        request.setProject("demoApp");
        request.setSharedConfig(Map.of("textContent", "smoke", "routingCode", "us"));
        request.setUserId("agent");
        request.setExecutionSpec(taskExecutionSpec(defaultMaxRetryCount));

        Task task = manager.createTaskShell(request);
        assertEquals(1, manager.appendTaskItems(task.getTid(), List.of(Map.of("target", "alpha"))));
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

    private static TaskExecutionSpec taskExecutionSpec(int defaultMaxRetryCount) {
        TaskExecutionSpec spec = new TaskExecutionSpec();
        spec.setBatchSize(1);
        spec.setDefaultMaxRetryCount(defaultMaxRetryCount);
        return spec;
    }

    private static void restoreProperty(String key, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previousValue);
        }
    }

    private static void awaitCondition(BooleanSupplier condition, String failureMessage) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadlineNanos) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(condition.getAsBoolean(), failureMessage);
    }

    private static final class FlakyCommitTaskResultRuntime implements TaskResultRuntime {
        private final InMemoryTaskResultRuntime delegate = new InMemoryTaskResultRuntime();
        private volatile boolean failNextVisibleCommit;
        private volatile boolean failNextAttemptClosedClaim;
        private volatile boolean blockRepairPumpScans;

        private void failNextVisibleCommit() {
            failNextVisibleCommit = true;
        }

        private void failNextAttemptClosedClaim() {
            failNextAttemptClosedClaim = true;
        }

        private void blockRepairPumpScans() {
            blockRepairPumpScans = true;
        }

        private void allowRepairPumpScans() {
            blockRepairPumpScans = false;
        }

        @Override
        public com.xa.mass.runtime.api.StageResult stageCallback(TaskResultCallbackDraft draft) {
            return delegate.stageCallback(draft);
        }

        @Override
        public boolean discardStagedCallback(String stageId) {
            return delegate.discardStagedCallback(stageId);
        }

        @Override
        public int discardStagedCallbacksForMessage(String taskId, String messageId) {
            return delegate.discardStagedCallbacksForMessage(taskId, messageId);
        }

        @Override
        public CommitResult commitVisibleFinal(TaskResultFinalDraft finalDraft) {
            if (failNextVisibleCommit) {
                failNextVisibleCommit = false;
                return CommitResult.unavailable("simulated visible commit failure");
            }
            return delegate.commitVisibleFinal(finalDraft);
        }

        @Override
        public List<TaskResultRepairCandidate> scanRepairCandidates(int limit) {
            if (blockRepairPumpScans && Thread.currentThread().getName().startsWith("engine-result-repair-")) {
                return List.of();
            }
            return delegate.scanRepairCandidates(limit);
        }

        @Override
        public BarrierClaim claimAttemptClosedPublish(String taskId, String messageId, long finalSeq) {
            if (failNextAttemptClosedClaim) {
                failNextAttemptClosedClaim = false;
                return BarrierClaim.unavailable();
            }
            return delegate.claimAttemptClosedPublish(taskId, messageId, finalSeq);
        }

        @Override
        public BarrierMarkResult markAttemptClosedPublished(String taskId,
                                                            String messageId,
                                                            long finalSeq,
                                                            String claimToken) {
            return delegate.markAttemptClosedPublished(taskId, messageId, finalSeq, claimToken);
        }

        @Override
        public BarrierClaim claimLogicalFinalPublish(String taskId, String messageId, long finalSeq) {
            return delegate.claimLogicalFinalPublish(taskId, messageId, finalSeq);
        }

        @Override
        public BarrierMarkResult markLogicalFinalPublished(String taskId,
                                                           String messageId,
                                                           long finalSeq,
                                                           String claimToken) {
            return delegate.markLogicalFinalPublished(taskId, messageId, finalSeq, claimToken);
        }

        @Override
        public BarrierClaim claimProgressApply(String taskId, String messageId, long finalSeq) {
            return delegate.claimProgressApply(taskId, messageId, finalSeq);
        }

        @Override
        public BarrierMarkResult markProgressApplied(String taskId,
                                                     String messageId,
                                                     long finalSeq,
                                                     String claimToken) {
            return delegate.markProgressApplied(taskId, messageId, finalSeq, claimToken);
        }

        @Override
        public TaskResultWindow readWindow(String taskId, long afterSeq, int limit) {
            return delegate.readWindow(taskId, afterSeq, limit);
        }

        @Override
        public long countVisibleResults(String taskId) {
            return delegate.countVisibleResults(taskId);
        }

        @Override
        public java.util.Optional<TaskResultRuntimeRow> getVisibleByMessageId(String taskId, String messageId) {
            return delegate.getVisibleByMessageId(taskId, messageId);
        }

        @Override
        public long discardTask(String taskId) {
            return delegate.discardTask(taskId);
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }
    }
}
