package com.xa.mass.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchDeliveryFailure;
import com.xa.mass.engine.policy.ContractAwareTaskTerminalPolicy;
import com.xa.mass.engine.strategy.DefaultSchedulingPlaneResolver;
import com.xa.mass.task.runtime.ClaimedWorkItem;
import com.xa.mass.task.runtime.TaskScoreV1;
import com.xa.mass.task.runtime.memory.InMemoryTaskRuntime;
import com.xa.mass.trace.sink.ExecutionEvent;
import com.xa.mass.trace.sink.ExecutionEventSink;
import com.xa.mass.trace.sink.ExecutionEventType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TaskRuntimeServingLaneTest {
    private static final long TEST_NOW_MILLIS = TaskScoreV1.TIME_SCORE_FLOOR;

    @Test
    void servingLaneDrivesAppendClaimWorkerResultAndTaskTerminalFromOneTaskRuntimeOwner() {
        AtomicLong clock = new AtomicLong(TEST_NOW_MILLIS);
        Harness harness = new Harness(clock);
        Task task = harness.createApprovedBatchTask("serving-result");
        List<TaskWorkAttemptClosedEvent> closedAttempts = new ArrayList<>();
        List<TaskWorkLogicallyFinalEvent> finalMessages = new ArrayList<>();
        harness.events.addTaskWorkAttemptClosedListener((ignored, event) -> closedAttempts.add(event));
        harness.events.addTaskWorkLogicallyFinalListener((ignored, event) -> finalMessages.add(event));

        harness.appendRuntimeItems(task, List.of(Map.of(
                "eventCode", "demo.event",
                "payloadRef", "payload-ref-1",
                "value", 1)));

        assertThat(harness.lane.countDispatchReadyWork(task.getTid())).isEqualTo(1);
        assertThat(harness.lane.getRuntimeDispatchableTasks(10))
                .extracting(Task::getTid)
                .containsExactly(task.getTid());

        var claimed = TaskRuntimeClaimTestSupport.claim(harness.lane,
                task.getTid(),
                "group-1",
                "worker-1",
                "batch-1",
                "selection-1",
                123L,
                1,
                30L).claimedItems();

        assertThat(claimed).hasSize(1);
        assertThat(claimed.getFirst().payloadRef()).isEqualTo("payload-ref-1");
        assertThat(harness.lane.countActiveDispatchWorkers(task.getTid())).isEqualTo(1);
        assertThat(harness.lane.getResultCorrelation(task.getTid(), claimed.getFirst().messageId()).activeLeasePresent())
                .isTrue();

        assertThat(harness.lane.ingestTaskResult(
                task.getTid(),
                claimed.getFirst().messageId(),
                true,
                "done",
                null,
                Map.of("ok", true))).isTrue();

        Task refreshed = harness.manager.getTask(task.getTid());
        assertThat(refreshed.getStatus()).isEqualTo(TaskStatus.TERMINAL);
        assertThat(refreshed.getTerminalReason()).isEqualTo(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED);
        assertThat(refreshed.getTaskSuccessNumber()).isEqualTo(1);
        assertThat(harness.lane.countDispatchReadyWork(task.getTid())).isZero();
        assertThat(harness.lane.countActiveDispatchWorkers(task.getTid())).isZero();
        assertThat(harness.runtime.activeWorkForTask(task.getTid(), 10).activeItems()).isEmpty();
        assertThat(harness.runtime.progressSnapshot(task.getTid()).readyCount()).isZero();
        assertThat(harness.runtime.progressSnapshot(task.getTid()).delayedCount()).isZero();
        assertThat(harness.runtime.taskScore(task.getTid(), "default"))
                .hasValueSatisfying(score -> assertThat(score.isTerminalBand()).isTrue());
        assertThat(closedAttempts).hasSize(1);
        assertThat(finalMessages).hasSize(1);
        assertThat(claimed.getFirst().scoreBandClaimScore()).isEqualTo(123L);
        assertThat(closedAttempts.getFirst().workerGroupId()).isEqualTo("group-1");
        assertThat(closedAttempts.getFirst().selectionToken()).isEqualTo("selection-1");
        assertThat(closedAttempts.getFirst().scoreBandClaimScore()).isEqualTo(123L);
    }

    @Test
    void servingLaneResultIngressUsesTaskRuntimeOwner() {
        AtomicLong clock = new AtomicLong(TEST_NOW_MILLIS);
        Harness harness = new Harness(clock);
        Task task = harness.createApprovedBatchTask("serving-manager-ingest");
        harness.appendRuntimeItems(task, List.of(Map.of(
                "eventCode", "demo.event",
                "payloadRef", "payload-ref-1",
                "value", 1)));
        var claimed = TaskRuntimeClaimTestSupport.claim(harness.lane,
                task.getTid(),
                "group-1",
                "worker-1",
                "batch-1",
                "selection-1",
                123L,
                1,
                30L).claimedItems();

        assertThat(harness.lane.getResultCorrelation(task.getTid(), claimed.getFirst().messageId())
                .activeLeasePresent()).isTrue();
        assertThat(harness.lane.ingestTaskResult(
                task.getTid(),
                claimed.getFirst().messageId(),
                true,
                "done-through-manager",
                null,
                Map.of("ok", true))).isTrue();

        Task refreshed = harness.manager.getTask(task.getTid());
        assertThat(refreshed.getStatus()).isEqualTo(TaskStatus.TERMINAL);
        assertThat(refreshed.getTerminalReason()).isEqualTo(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED);
        assertThat(harness.lane.countVisibleTaskResults(task.getTid())).isEqualTo(1);
    }

    @Test
    void servingLaneRecoveryAndClaimUseTaskRuntimeOwner() {
        AtomicLong clock = new AtomicLong(TEST_NOW_MILLIS);
        Harness harness = new Harness(clock);
        Task task = harness.createApprovedBatchTask("serving-manager-claim");
        harness.appendRuntimeItems(task, List.of(Map.of(
                "eventCode", "demo.event",
                "payloadRef", "payload-ref-claim",
                "value", 1)));

        assertThat(harness.lane.getRuntimeDispatchableTasks(10))
                .extracting(Task::getTid)
                .containsExactly(task.getTid());

        var claimed = TaskRuntimeClaimTestSupport.claim(harness.lane,
                task.getTid(),
                "group-1",
                "worker-1",
                "batch-1",
                "selection-1",
                123L,
                1,
                30L).claimedItems();

        assertThat(claimed).hasSize(1);
        assertThat(claimed.getFirst().messageId()).isEqualTo("message-1");
        assertThat(claimed.getFirst().payloadRef()).isEqualTo("payload-ref-claim");
        assertThat(harness.lane.countActiveDispatchWorkers(task.getTid())).isEqualTo(1);
    }

    @Test
    void taskManagerDispatchFailureCompensationUsesServingLaneRuntimeOwnerWhenInstalled() {
        AtomicLong clock = new AtomicLong(TEST_NOW_MILLIS);
        Harness harness = new Harness(clock);
        Task submitFailureTask = harness.createApprovedBatchTask("serving-submit-failure");
        harness.appendRuntimeItems(submitFailureTask, List.of(Map.of("eventCode", "demo.event", "value", 1)));
        harness.manager.installTaskRuntimeServingLane(harness.lane);
        var submitClaim = claimThroughLane(harness, submitFailureTask, "batch-1", "selection-1", 123L);

        assertThat(harness.lane.compensateDispatchSubmitFailure(
                submitFailureTask,
                List.of(dispatchBinding(submitFailureTask, submitClaim.getFirst())),
                "transport submit failed")).isTrue();

        Task refreshedSubmitFailureTask = harness.manager.getTask(submitFailureTask.getTid());
        assertThat(refreshedSubmitFailureTask.getStatus()).isEqualTo(TaskStatus.TERMINAL);
        assertThat(refreshedSubmitFailureTask.getTerminalReason()).isEqualTo(TaskTerminalReason.ALL_MESSAGES_FAILED);
        assertThat(harness.lane.countVisibleTaskResults(submitFailureTask.getTid())).isEqualTo(1);

        Task deliveryFailureTask = harness.createApprovedBatchTask("serving-delivery-failure");
        harness.appendRuntimeItems(deliveryFailureTask, List.of(Map.of("eventCode", "demo.event", "value", 2)));
        var deliveryClaim = claimThroughLane(harness, deliveryFailureTask, "batch-2", "selection-2", 124L);
        ClaimedWorkItem claimed = deliveryClaim.getFirst();

        assertThat(harness.lane.compensateDispatchDeliveryFailure(
                deliveryFailureTask,
                List.of(new TaskDispatchDeliveryFailure(
                        deliveryFailureTask.getTid(),
                        claimed.messageId(),
                        attemptId(claimed),
                        1,
                        claimed.workerId(),
                        "delivery failed")))).isTrue();

        Task refreshedDeliveryFailureTask = harness.manager.getTask(deliveryFailureTask.getTid());
        assertThat(refreshedDeliveryFailureTask.getStatus()).isEqualTo(TaskStatus.TERMINAL);
        assertThat(refreshedDeliveryFailureTask.getTerminalReason()).isEqualTo(TaskTerminalReason.ALL_MESSAGES_FAILED);
        assertThat(harness.lane.countVisibleTaskResults(deliveryFailureTask.getTid())).isEqualTo(1);
    }

    @Test
    void servingLaneLeaseRepairAppliesTimeoutResultAndConvergesTaskTerminal() {
        AtomicLong clock = new AtomicLong(TEST_NOW_MILLIS);
        Harness harness = new Harness(clock);
        Task task = harness.createApprovedBatchTask("serving-timeout");
        harness.appendRuntimeItems(task, List.of(Map.of("eventCode", "demo.event", "value", 1)));
        var claimed = TaskRuntimeClaimTestSupport.claim(harness.lane,
                task.getTid(),
                "group-1",
                "worker-1",
                "batch-1",
                "selection-1",
                123L,
                1,
                1L).claimedItems();

        clock.set(TEST_NOW_MILLIS + 2_000L);

        var expired = harness.lane.pollExpiredLeases(10, Instant.ofEpochMilli(clock.get()));
        assertThat(expired).hasSize(1);
        assertThat(expired.getFirst().workerGroupId()).isEqualTo("group-1");
        assertThat(expired.getFirst().batchId()).isEqualTo("batch-1");
        assertThat(expired.getFirst().scoreBandClaimScore()).isEqualTo(123L);

        assertThat(harness.lane.expireLeasedWork(task.getTid(), claimed.getFirst().messageId())).isTrue();

        Task refreshed = harness.manager.getTask(task.getTid());
        assertThat(refreshed.getStatus()).isEqualTo(TaskStatus.TERMINAL);
        assertThat(refreshed.getTerminalReason()).isEqualTo(TaskTerminalReason.ALL_MESSAGES_FAILED);
        assertThat(harness.lane.getResultCorrelation(task.getTid(), claimed.getFirst().messageId()).activeLeasePresent())
                .isFalse();
    }

    @Test
    void retryableLeaseExpiryEmitsRetryResetTrace() {
        AtomicLong clock = new AtomicLong(TEST_NOW_MILLIS);
        Harness harness = new Harness(clock);
        Task task = harness.createApprovedBatchTask("serving-timeout-retry", 1);
        harness.appendRuntimeItems(task, List.of(Map.of("eventCode", "demo.event", "value", 1)));
        var claimed = TaskRuntimeClaimTestSupport.claim(harness.lane,
                task.getTid(),
                "group-1",
                "worker-1",
                "batch-1",
                "selection-1",
                123L,
                1,
                1L).claimedItems();

        task.setStatus(TaskStatus.PAUSED);
        clock.set(TEST_NOW_MILLIS + 2_000L);

        assertThat(harness.lane.expireLeasedWork(task.getTid(), claimed.getFirst().messageId())).isTrue();

        List<ExecutionEvent> retryResetEvents = harness.traceSink.eventsOfType(ExecutionEventType.TASK_WORK_RETRY_RESET);
        assertThat(retryResetEvents).hasSize(1);
        ExecutionEvent retryReset = retryResetEvents.getFirst();
        assertThat(retryReset.getIdentity().taskId()).isEqualTo(task.getTid());
        assertThat(retryReset.getIdentity().messageId()).isEqualTo(claimed.getFirst().messageId());
        assertThat(retryReset.getIdentity().workerId()).isEqualTo("worker-1");
        assertThat(retryReset.getTransition().src()).isEqualTo("EXPIRED");
        assertThat(retryReset.getTransition().dst()).isEqualTo("INIT");
        assertThat(retryReset.getAttrs()).containsEntry("source", "TaskRuntimeServingLane");
        assertThat(retryReset.getAttrs()).containsEntry("trigger", "LEASE_TIMEOUT");
        assertThat(harness.runtime.taskScore(task.getTid(), "default"))
                .hasValueSatisfying(score -> {
                    assertThat(score.isSchedulableBand()).isTrue();
                    assertThat(score.score()).isGreaterThanOrEqualTo(clock.get());
                });
    }

    @Test
    void taskManagerFullDiscardUsesServingLaneRuntimeOwner() {
        AtomicLong clock = new AtomicLong(TEST_NOW_MILLIS);
        Harness harness = new Harness(clock);
        Task task = harness.createApprovedBatchTask("serving-discard");
        harness.appendRuntimeItems(task, List.of(Map.of("eventCode", "demo.event", "value", 1)));
        var claimed = TaskRuntimeClaimTestSupport.claim(harness.lane,
                task.getTid(),
                "group-1",
                "worker-1",
                "batch-1",
                "selection-1",
                123L,
                1,
                30L).claimedItems();
        assertThat(harness.lane.ingestTaskResult(
                task.getTid(),
                claimed.getFirst().messageId(),
                true,
                "done",
                null,
                Map.of("ok", true))).isTrue();
        assertThat(harness.lane.countVisibleTaskResults(task.getTid())).isEqualTo(1);

        harness.manager.installTaskRuntimeServingLane(harness.lane);
        harness.manager.discardTaskRuntime(task.getTid());

        assertThat(harness.lane.countDispatchReadyWork(task.getTid())).isZero();
        assertThat(harness.lane.countActiveDispatchWorkers(task.getTid())).isZero();
        assertThat(harness.lane.countVisibleTaskResults(task.getTid())).isZero();
        assertThat(harness.lane.readTaskResults(task.getTid(), 0, 10).rows()).isEmpty();
        assertThat(harness.lane.getRuntimeDispatchableTasks(10)).isEmpty();
    }

    @Test
    void taskManagerWorkOnlyDiscardUsesServingLaneAndKeepsFinalResults() {
        AtomicLong clock = new AtomicLong(TEST_NOW_MILLIS);
        Harness harness = new Harness(clock);
        Task task = harness.createApprovedBatchTask("serving-work-discard");
        harness.appendRuntimeItems(task, List.of(
                Map.of("eventCode", "demo.event", "value", 1),
                Map.of("eventCode", "demo.event", "value", 2),
                Map.of("eventCode", "demo.event", "value", 3)));
        var firstClaim = TaskRuntimeClaimTestSupport.claim(harness.lane,
                task.getTid(),
                "group-1",
                "worker-1",
                "batch-1",
                "selection-1",
                123L,
                1,
                30L).claimedItems();
        assertThat(harness.lane.ingestTaskResult(
                task.getTid(),
                firstClaim.getFirst().messageId(),
                true,
                "done",
                null,
                Map.of("ok", true))).isTrue();
        TaskRuntimeClaimTestSupport.claim(harness.lane,
                task.getTid(),
                "group-1",
                "worker-1",
                "batch-2",
                "selection-2",
                124L,
                1,
                30L);
        assertThat(harness.lane.countVisibleTaskResults(task.getTid())).isEqualTo(1);
        assertThat(harness.lane.countActiveDispatchWorkers(task.getTid())).isEqualTo(1);
        assertThat(harness.lane.countDispatchReadyWork(task.getTid())).isEqualTo(1);

        harness.manager.installTaskRuntimeServingLane(harness.lane);
        harness.manager.discardTaskWork(task.getTid());

        assertThat(harness.lane.countDispatchReadyWork(task.getTid())).isZero();
        assertThat(harness.lane.countActiveDispatchWorkers(task.getTid())).isZero();
        assertThat(harness.lane.countVisibleTaskResults(task.getTid())).isEqualTo(1);
        assertThat(harness.lane.readTaskResults(task.getTid(), 0, 10).rows())
                .extracting(row -> row.messageId())
                .containsExactly(firstClaim.getFirst().messageId());
    }

    @Test
    void dispatchWakeupPublishesForNonTerminalProjectionWithoutRewritingRuntimeScore() {
        AtomicLong clock = new AtomicLong(TEST_NOW_MILLIS);
        Harness harness = new Harness(clock);
        Task task = harness.createApprovedBatchTask("serving-wakeup");
        harness.appendRuntimeItems(task, List.of(Map.of("eventCode", "demo.event", "value", 1)));
        TaskScoreV1 scoreBefore = harness.runtime.taskScore(task.getTid(), "default").orElseThrow();
        AtomicInteger dispatchRequests = new AtomicInteger();
        harness.events.addTaskDispatchListener(ignored -> dispatchRequests.incrementAndGet());

        task.setStatus(TaskStatus.PAUSED);
        harness.lane.requestTaskDispatch(task);

        assertThat(dispatchRequests).hasValue(1);
        assertThat(harness.runtime.taskScore(task.getTid(), "default"))
                .hasValue(scoreBefore);
    }

    private static List<ClaimedWorkItem> claimThroughLane(Harness harness,
                                                          Task task,
                                                          String batchId,
                                                          String selectionToken,
                                                          long scoreBandClaimScore) {
        var claimed = TaskRuntimeClaimTestSupport.claim(harness.lane,
                task.getTid(),
                "group-1",
                "worker-1",
                batchId,
                selectionToken,
                scoreBandClaimScore,
                1,
                30L).claimedItems();
        assertThat(claimed).hasSize(1);
        return claimed;
    }

    private static TaskDispatchBinding dispatchBinding(Task task, ClaimedWorkItem work) {
        return TaskDispatchBinding.workerLevelWithEvidence(
                task.getTid(),
                work.messageId(),
                work.eventCode(),
                work.payloadJson(),
                work.payloadRef(),
                Math.max(0, work.attemptNo() - 1),
                attemptId(work),
                work.attemptNo(),
                work.leaseToken(),
                work.workerId(),
                work.batchId(),
                work.workerGroupId(),
                work.workerReservationToken(),
                work.scoreBandClaimScore(),
                task.getProject() + ":" + work.eventCode(),
                "GROUP_SELECTOR");
    }

    private static String attemptId(ClaimedWorkItem work) {
        return TaskWorkAttemptIdSupport.workerLevelRuntimeAttemptId(
                work.messageId(),
                work.attemptNo(),
                work.workerId(),
                work.batchId());
    }

    private static final class Harness {
        private final InMemoryTaskRuntime runtime;
        private final TaskManager manager;
        private final TaskEventService events;
        private final TaskRuntimeServingLane lane;
        private final CapturingTraceSink traceSink;

        private Harness(AtomicLong clock) {
            this.runtime = new InMemoryTaskRuntime(clock::get);
            this.traceSink = new CapturingTraceSink();
            var taskStorage = new InMemoryTaskShellRuntimeStore();
            this.manager = new TaskManager(
                    taskStorage,
                    taskStorage,
                    new ContractAwareTaskTerminalPolicy(),
                    null);
            this.events = new TaskEventService(manager);
            this.lane = TaskRuntimeServingLaneTestSupport.forTaskManager(
                    runtime,
                    runtime,
                    runtime,
                    runtime,
                    runtime,
                    manager,
                    new ContractAwareTaskTerminalPolicy(),
                    new DefaultSchedulingPlaneResolver(),
                    new TraceEventLogger(traceSink),
                    1L,
                    100,
                    86_400_000L,
                    clock::get);
            this.manager.installTaskRuntimeServingLane(lane);
        }

        private Task createApprovedBatchTask(String name) {
            return createApprovedBatchTask(name, 0);
        }

        private Task createApprovedBatchTask(String name, int maxRetryCount) {
            TaskShellCreateRequestDto dto = new TaskShellCreateRequestDto();
            dto.setProject("demoApp");
            dto.setUserId("agent");
            dto.setSourceRef(name);
            dto.setContract(TaskContract.BATCH);
            dto.setExecutionSpec(taskExecutionSpec(maxRetryCount));
            dto.setSharedConfig(Map.of(TaskSharedConfig.WORKER_GROUP_ID, "group-1"));
            var create = manager.createTaskShell(dto);
            assertThat(create.accepted()).isTrue();
            Task task = manager.getTask(create.taskId());
            assertThat(task).isNotNull();
            assertThat(manager.approveTask(task.getTid()).accepted()).isTrue();
            return manager.getTask(task.getTid());
        }

        private void appendRuntimeItems(Task task, List<Map<String, Object>> inputs) {
            List<RuntimeTaskIngressItem> ingressItems = new ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                ingressItems.add(RuntimeTaskIngressItem.fromInput(
                        task.getTid(),
                        "message-" + (i + 1),
                        inputs.get(i),
                        task.getExecutionSpec().getDefaultMaxRetryCount()));
            }
            lane.appendRuntimeIngressItems(task, ingressItems);
            task.setTaskTargetNumber(task.getTaskTargetNumber() + ingressItems.size());
            task.setTaskEligibleNumber(task.getTaskEligibleNumber() + ingressItems.size());
            manager.persistTaskShell(task);
            assertThat(manager.sealTask(task.getTid()).accepted()).isTrue();
        }

        private TaskExecutionSpec taskExecutionSpec() {
            return taskExecutionSpec(0);
        }

        private TaskExecutionSpec taskExecutionSpec(int maxRetryCount) {
            TaskExecutionSpec spec = new TaskExecutionSpec();
            spec.setDefaultMaxRetryCount(maxRetryCount);
            spec.setBatchSize(1);
            return spec;
        }

    }

    private static final class CapturingTraceSink implements ExecutionEventSink {
        private final List<ExecutionEvent> events = new ArrayList<>();

        @Override
        public synchronized void emit(ExecutionEvent event) {
            events.add(event);
        }

        private synchronized List<ExecutionEvent> eventsOfType(ExecutionEventType eventType) {
            return events.stream()
                    .filter(event -> event.getEventType() == eventType)
                    .toList();
        }
    }
}
