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
import com.xa.mass.task.runtime.ClaimedWorkItem;
import com.xa.mass.task.runtime.memory.InMemoryTaskRuntime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TaskRuntimeServingLaneTest {

    @Test
    void servingLaneDrivesAppendClaimWorkerResultAndTaskTerminalFromOneTaskRuntimeOwner() {
        AtomicLong clock = new AtomicLong(1_000L);
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

        var claimed = harness.lane.claimReady(TaskRuntimeClaimTestSupport.claimCommand(
                task.getTid(),
                "group-1",
                "worker-1",
                "batch-1",
                "selection-1",
                123L,
                1,
                30L)).claimedItems();

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
        assertThat(closedAttempts).hasSize(1);
        assertThat(finalMessages).hasSize(1);
        assertThat(claimed.getFirst().scoreBandClaimScore()).isEqualTo(123L);
        assertThat(closedAttempts.getFirst().workerGroupId()).isEqualTo("group-1");
        assertThat(closedAttempts.getFirst().selectionToken()).isEqualTo("selection-1");
        assertThat(closedAttempts.getFirst().scoreBandClaimScore()).isEqualTo(123L);
    }

    @Test
    void servingLaneResultIngressUsesTaskRuntimeOwner() {
        AtomicLong clock = new AtomicLong(1_000L);
        Harness harness = new Harness(clock);
        Task task = harness.createApprovedBatchTask("serving-manager-ingest");
        harness.appendRuntimeItems(task, List.of(Map.of(
                "eventCode", "demo.event",
                "payloadRef", "payload-ref-1",
                "value", 1)));
        var claimed = harness.lane.claimReady(TaskRuntimeClaimTestSupport.claimCommand(
                task.getTid(),
                "group-1",
                "worker-1",
                "batch-1",
                "selection-1",
                123L,
                1,
                30L)).claimedItems();

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
        AtomicLong clock = new AtomicLong(1_000L);
        Harness harness = new Harness(clock);
        Task task = harness.createApprovedBatchTask("serving-manager-claim");
        harness.appendRuntimeItems(task, List.of(Map.of(
                "eventCode", "demo.event",
                "payloadRef", "payload-ref-claim",
                "value", 1)));

        assertThat(harness.lane.getRuntimeDispatchableTasks(10))
                .extracting(Task::getTid)
                .containsExactly(task.getTid());

        var claimed = harness.lane.claimReady(TaskRuntimeClaimTestSupport.claimCommand(
                task.getTid(),
                "group-1",
                "worker-1",
                "batch-1",
                "selection-1",
                123L,
                1,
                30L)).claimedItems();

        assertThat(claimed).hasSize(1);
        assertThat(claimed.getFirst().messageId()).isEqualTo("message-1");
        assertThat(claimed.getFirst().payloadRef()).isEqualTo("payload-ref-claim");
        assertThat(harness.lane.countActiveDispatchWorkers(task.getTid())).isEqualTo(1);
    }

    @Test
    void taskManagerDispatchFailureCompensationUsesServingLaneRuntimeOwnerWhenInstalled() {
        AtomicLong clock = new AtomicLong(1_000L);
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
        AtomicLong clock = new AtomicLong(1_000L);
        Harness harness = new Harness(clock);
        Task task = harness.createApprovedBatchTask("serving-timeout");
        harness.appendRuntimeItems(task, List.of(Map.of("eventCode", "demo.event", "value", 1)));
        var claimed = harness.lane.claimReady(TaskRuntimeClaimTestSupport.claimCommand(
                task.getTid(),
                "group-1",
                "worker-1",
                "batch-1",
                "selection-1",
                123L,
                1,
                1L)).claimedItems();

        clock.set(3_000L);

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
    void taskManagerFullDiscardUsesServingLaneRuntimeOwner() {
        AtomicLong clock = new AtomicLong(1_000L);
        Harness harness = new Harness(clock);
        Task task = harness.createApprovedBatchTask("serving-discard");
        harness.appendRuntimeItems(task, List.of(Map.of("eventCode", "demo.event", "value", 1)));
        var claimed = harness.lane.claimReady(TaskRuntimeClaimTestSupport.claimCommand(
                task.getTid(),
                "group-1",
                "worker-1",
                "batch-1",
                "selection-1",
                123L,
                1,
                30L)).claimedItems();
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
        AtomicLong clock = new AtomicLong(1_000L);
        Harness harness = new Harness(clock);
        Task task = harness.createApprovedBatchTask("serving-work-discard");
        harness.appendRuntimeItems(task, List.of(
                Map.of("eventCode", "demo.event", "value", 1),
                Map.of("eventCode", "demo.event", "value", 2),
                Map.of("eventCode", "demo.event", "value", 3)));
        var firstClaim = harness.lane.claimReady(TaskRuntimeClaimTestSupport.claimCommand(
                task.getTid(),
                "group-1",
                "worker-1",
                "batch-1",
                "selection-1",
                123L,
                1,
                30L)).claimedItems();
        assertThat(harness.lane.ingestTaskResult(
                task.getTid(),
                firstClaim.getFirst().messageId(),
                true,
                "done",
                null,
                Map.of("ok", true))).isTrue();
        harness.lane.claimReady(TaskRuntimeClaimTestSupport.claimCommand(
                task.getTid(),
                "group-1",
                "worker-1",
                "batch-2",
                "selection-2",
                124L,
                1,
                30L));
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

    private static List<ClaimedWorkItem> claimThroughLane(Harness harness,
                                                          Task task,
                                                          String batchId,
                                                          String selectionToken,
                                                          long scoreBandClaimScore) {
        var claimed = harness.lane.claimReady(TaskRuntimeClaimTestSupport.claimCommand(
                task.getTid(),
                "group-1",
                "worker-1",
                batchId,
                selectionToken,
                scoreBandClaimScore,
                1,
                30L)).claimedItems();
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

        private Harness(AtomicLong clock) {
            this.runtime = new InMemoryTaskRuntime(clock::get);
            var taskStorage = new InMemoryTaskShellRuntimeStore();
            this.manager = new TaskManager(
                    taskStorage,
                    taskStorage,
                    new ContractAwareTaskTerminalPolicy(),
                    null);
            var commands = new TaskCommandService(manager);
            var queries = new TaskQueryService(manager);
            this.events = new TaskEventService(manager);
            this.lane = new TaskRuntimeServingLane(
                    runtime,
                    runtime,
                    runtime,
                    runtime,
                    runtime,
                    runtime,
                    queries,
                    commands,
                    events,
                    1L,
                    100,
                    86_400_000L);
            this.manager.installTaskRuntimeServingLane(lane);
        }

        private Task createApprovedBatchTask(String name) {
            TaskShellCreateRequestDto dto = new TaskShellCreateRequestDto();
            dto.setProject("demoApp");
            dto.setUserId("agent");
            dto.setSourceRef(name);
            dto.setContract(TaskContract.BATCH);
            dto.setExecutionSpec(taskExecutionSpec());
            dto.setSharedConfig(Map.of(TaskSharedConfig.WORKER_GROUP_ID, "group-1"));
            Task task = manager.createTaskShell(dto);
            assertThat(manager.approveTask(task.getTid())).isTrue();
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
            manager.updateTask(task);
            assertThat(manager.sealTask(task.getTid())).isTrue();
        }

        private TaskExecutionSpec taskExecutionSpec() {
            TaskExecutionSpec spec = new TaskExecutionSpec();
            spec.setDefaultMaxRetryCount(0);
            spec.setBatchSize(1);
            return spec;
        }

    }
}
