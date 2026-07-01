package com.xa.mass.task.runtime.memory;

import com.xa.mass.task.runtime.ActiveLeaseRepairBatch;
import com.xa.mass.task.runtime.ActiveLeaseRepairCandidate;
import com.xa.mass.task.runtime.ActiveTaskWorkQuery;
import com.xa.mass.task.runtime.ActiveTaskWorkSnapshot;
import com.xa.mass.task.runtime.ActiveWorkQuery;
import com.xa.mass.task.runtime.ActiveWorkSnapshot;
import com.xa.mass.task.runtime.AppendAdmissionPolicy;
import com.xa.mass.task.runtime.AppendBatchCommand;
import com.xa.mass.task.runtime.AppendBatchOutcome;
import com.xa.mass.task.runtime.ClaimReadyCommand;
import com.xa.mass.task.runtime.ClaimReadyOutcome;
import com.xa.mass.task.runtime.ClaimedWorkItem;
import com.xa.mass.task.runtime.DiscardTaskRuntimeCommand;
import com.xa.mass.task.runtime.DiscardTaskRuntimeOutcome;
import com.xa.mass.task.runtime.DiscardTaskWorkCommand;
import com.xa.mass.task.runtime.DiscardTaskWorkOutcome;
import com.xa.mass.task.runtime.FinalResultReadRequest;
import com.xa.mass.task.runtime.FinalResultRow;
import com.xa.mass.task.runtime.FinalResultWindow;
import com.xa.mass.task.runtime.MessageFinalityOutcome;
import com.xa.mass.task.runtime.MessageFinalityStatus;
import com.xa.mass.task.runtime.PollActiveLeaseRepairCommand;
import com.xa.mass.task.runtime.ResultApplyCommand;
import com.xa.mass.task.runtime.ResultApplySource;
import com.xa.mass.task.runtime.ResultCorrelationSnapshot;
import com.xa.mass.task.runtime.RetryMode;
import com.xa.mass.task.runtime.RuntimeEpoch;
import com.xa.mass.task.runtime.RuntimeGate;
import com.xa.mass.task.runtime.SchedulerDiscoveryCommand;
import com.xa.mass.task.runtime.SchedulerDiscoveryOutcome;
import com.xa.mass.task.runtime.SchedulerEligibilityPolicy;
import com.xa.mass.task.runtime.SchedulerTaskCandidate;
import com.xa.mass.task.runtime.TaskRuntimeAppendPort;
import com.xa.mass.task.runtime.TaskRuntimeClaimPort;
import com.xa.mass.task.runtime.TaskRuntimeDiscardPort;
import com.xa.mass.task.runtime.TaskRuntimeProgressPort;
import com.xa.mass.task.runtime.TaskRuntimeProgressSnapshot;
import com.xa.mass.task.runtime.TaskRuntimeReadPort;
import com.xa.mass.task.runtime.TaskRuntimeRepairPort;
import com.xa.mass.task.runtime.TaskRuntimeResultPort;
import com.xa.mass.task.runtime.TaskRuntimeSchedulerPort;
import com.xa.mass.task.runtime.UpdateSchedulerEligibilityCommand;
import com.xa.mass.task.runtime.WorkerReservationEvidence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class InMemoryTaskRuntime implements TaskRuntimeAppendPort,
        TaskRuntimeSchedulerPort,
        TaskRuntimeClaimPort,
        TaskRuntimeResultPort,
        TaskRuntimeRepairPort,
        TaskRuntimeProgressPort,
        TaskRuntimeReadPort,
        TaskRuntimeDiscardPort {

    private final Map<String, TaskState> tasks = new LinkedHashMap<>();
    private final LinkedHashSet<String> dirtyTasks = new LinkedHashSet<>();
    private final LongSupplier clock;

    public InMemoryTaskRuntime() {
        this(System::currentTimeMillis);
    }

    public InMemoryTaskRuntime(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized AppendBatchOutcome appendBatch(AppendBatchCommand command) {
        var state = taskState(command.taskId());
        var messageIds = command.items().stream().map(item -> item.messageId()).toList();
        var existingCount = messageIds.stream().filter(state::containsMessage).count();
        if (existingCount == messageIds.size()) {
            return AppendBatchOutcome.allAccepted(command.taskId(), messageIds);
        }
        if (existingCount > 0) {
            return AppendBatchOutcome.rejectedBeforeRuntime(command.taskId(), "batch mixes existing and new items");
        }
        var maxBacklog = command.admissionPolicy().maxReadyBacklogItems();
        if (maxBacklog != AppendAdmissionPolicy.UNLIMITED_READY_BACKLOG
                && state.pendingBacklogSize() + command.items().size() > maxBacklog) {
            return AppendBatchOutcome.rejectedBeforeRuntime(command.taskId(), "ready backlog is full");
        }
        for (var item : command.items()) {
            state.ready.addLast(ReadyItem.initial(
                    command.taskId(),
                    item.messageId(),
                    item.eventCode(),
                    item.payloadJson(),
                    item.payloadRef(),
                    command.runtimeEpoch()));
        }
        state.runtimeEpoch = command.runtimeEpoch();
        dirtyTasks.add(command.taskId());
        return AppendBatchOutcome.allAccepted(command.taskId(), messageIds);
    }

    @Override
    public synchronized void updateTaskEligibility(UpdateSchedulerEligibilityCommand command) {
        var state = taskState(command.taskId());
        state.eligibility = command.eligibilityPolicy();
        state.runtimeEpoch = command.runtimeEpoch();
        dirtyTasks.add(command.taskId());
    }

    @Override
    public synchronized SchedulerDiscoveryOutcome discoverEligibleTasks(SchedulerDiscoveryCommand command) {
        promoteDueRetries(command.nowMillis());
        var candidates = new ArrayList<SchedulerTaskCandidate>();
        for (var entry : tasks.entrySet()) {
            if (candidates.size() >= command.limit()) {
                break;
            }
            var taskId = entry.getKey();
            var state = entry.getValue();
            if (state.ready.isEmpty()) {
                dirtyTasks.remove(taskId);
                continue;
            }
            var eligibility = state.eligibility == null ? defaultEligibility() : state.eligibility;
            if (eligibility.runtimeGate() != RuntimeGate.OPEN || eligibility.nextEligibleAtMillis() > command.nowMillis()) {
                continue;
            }
            candidates.add(new SchedulerTaskCandidate(taskId, state.runtimeEpoch, eligibility.nextEligibleAtMillis()));
        }
        return new SchedulerDiscoveryOutcome(candidates);
    }

    @Override
    public synchronized void markTaskDirty(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            dirtyTasks.add(taskId);
        }
    }

    @Override
    public synchronized ClaimReadyOutcome claimReady(ClaimReadyCommand command) {
        var state = tasks.get(command.taskId());
        if (state == null || state.ready.isEmpty()) {
            return new ClaimReadyOutcome(command.taskId(), List.of(), "no ready work");
        }
        var expectedEpoch = command.leasePolicy().expectedRuntimeEpoch();
        var first = state.ready.peekFirst();
        if (first == null || !sameEpoch(first.runtimeEpoch(), expectedEpoch)) {
            return new ClaimReadyOutcome(command.taskId(), List.of(), "runtime epoch mismatch");
        }
        var claimLimit = Math.min(command.leasePolicy().maxItems(), state.ready.size());
        var claimed = new ArrayList<ClaimedWorkItem>();
        for (int index = 0; index < claimLimit && !state.ready.isEmpty(); index++) {
            var ready = state.ready.removeFirst();
            var reservation = command.workerReservations().get(index % command.workerReservations().size());
            var leaseToken = UUID.randomUUID().toString();
            var leaseExpireAtMillis = clock.getAsLong() + command.leasePolicy().leaseMillis();
            var active = new ActiveItem(ready, reservation, leaseToken, leaseExpireAtMillis);
            state.activeByMessageId.put(ready.messageId(), active);
            claimed.add(toClaimed(active));
        }
        if (state.ready.isEmpty()) {
            dirtyTasks.remove(command.taskId());
        }
        return new ClaimReadyOutcome(command.taskId(), claimed, "");
    }

    @Override
    public synchronized MessageFinalityOutcome applyResult(ResultApplyCommand command) {
        var state = tasks.get(command.taskId());
        if (state == null) {
            return rejected(command, "task runtime state not found");
        }
        var active = state.activeByMessageId.get(command.messageId());
        if (active == null) {
            return state.finalRowsByMessageId.containsKey(command.messageId())
                    ? MessageFinalityOutcome.duplicateOrLate(
                    command.taskId(), command.messageId(), command.attemptNo(), "already final")
                    : rejected(command, "active lease not found");
        }
        if (!active.leaseToken().equals(command.leaseToken())
                || !active.reservation().workerId().equals(command.workerId())
                || active.ready().attemptNo() != command.attemptNo()) {
            return rejected(command, "active lease correlation mismatch");
        }
        state.activeByMessageId.remove(command.messageId());
        if (command.success()) {
            addFinalRow(state, command, active, command.observedAtMillis());
            return MessageFinalityOutcome.logicalFinal(
                    command.taskId(),
                    command.messageId(),
                    command.attemptNo(),
                    finalExpiresAt(command));
        }
        if (canRetry(command)) {
            var retryAtMillis = command.observedAtMillis() + command.retryPolicy().retryDelayMillis();
            var retry = active.ready().nextAttempt(command.runtimeEpoch());
            if (command.retryPolicy().retryMode() == RetryMode.DUE_TIME && retryAtMillis > command.observedAtMillis()) {
                state.delayed.add(new DelayedItem(retry, retryAtMillis));
            } else {
                state.ready.addLast(retry);
                dirtyTasks.add(command.taskId());
            }
            return MessageFinalityOutcome.retryScheduled(
                    command.taskId(),
                    command.messageId(),
                    command.attemptNo(),
                    retryAtMillis,
                    command.failureReason());
        }
        addFinalRow(state, command, active, command.observedAtMillis());
        return MessageFinalityOutcome.logicalFinal(
                command.taskId(),
                command.messageId(),
                command.attemptNo(),
                finalExpiresAt(command));
    }

    @Override
    public synchronized ResultCorrelationSnapshot getResultCorrelation(String taskId, String messageId) {
        var state = tasks.get(taskId);
        if (state == null) {
            return ResultCorrelationSnapshot.missing(taskId, messageId);
        }
        var active = state.activeByMessageId.get(messageId);
        if (active == null) {
            return ResultCorrelationSnapshot.missing(taskId, messageId);
        }
        return new ResultCorrelationSnapshot(
                taskId,
                messageId,
                active.leaseToken(),
                active.reservation().workerId(),
                active.ready().attemptNo(),
                true);
    }

    @Override
    public synchronized ActiveLeaseRepairBatch pollExpiredActiveLeases(PollActiveLeaseRepairCommand command) {
        var candidates = new ArrayList<ActiveLeaseRepairCandidate>();
        for (var state : tasks.values()) {
            for (var active : state.activeByMessageId.values()) {
                if (candidates.size() >= command.limit()) {
                    return new ActiveLeaseRepairBatch(candidates);
                }
                if (active.leaseExpireAtMillis() <= command.nowMillis()) {
                    candidates.add(toRepairCandidate(active));
                }
            }
        }
        return new ActiveLeaseRepairBatch(candidates);
    }

    @Override
    public synchronized ActiveTaskWorkSnapshot getActiveWorkForTask(ActiveTaskWorkQuery query) {
        var state = tasks.get(query.taskId());
        if (state == null || state.activeByMessageId.isEmpty()) {
            return new ActiveTaskWorkSnapshot(query.taskId(), List.of());
        }
        var activeItems = new ArrayList<ActiveLeaseRepairCandidate>();
        for (var active : state.activeByMessageId.values()) {
            if (activeItems.size() >= query.limit()) {
                break;
            }
            activeItems.add(toRepairCandidate(active));
        }
        return new ActiveTaskWorkSnapshot(query.taskId(), activeItems);
    }

    @Override
    public synchronized ActiveWorkSnapshot getActiveWorkForWorker(ActiveWorkQuery query) {
        var activeItems = new ArrayList<ActiveLeaseRepairCandidate>();
        for (var state : tasks.values()) {
            for (var active : state.activeByMessageId.values()) {
                if (activeItems.size() >= query.limit()) {
                    return new ActiveWorkSnapshot(query.workerId(), activeItems);
                }
                if (active.reservation().workerId().equals(query.workerId())) {
                    activeItems.add(toRepairCandidate(active));
                }
            }
        }
        return new ActiveWorkSnapshot(query.workerId(), activeItems);
    }

    @Override
    public synchronized FinalResultWindow readFinalResults(FinalResultReadRequest request) {
        var state = tasks.get(request.taskId());
        if (state == null) {
            return new FinalResultWindow(request.taskId(), List.of(), request.afterSeq(), false, 0L);
        }
        purgeExpiredFinalRows(state, clock.getAsLong());
        var rows = new ArrayList<FinalResultRow>();
        var hasMore = false;
        for (var row : state.finalRowsByMessageId.values()) {
            if (row.seq() <= request.afterSeq()) {
                continue;
            }
            if (rows.size() >= request.limit()) {
                hasMore = true;
                break;
            }
            rows.add(row);
        }
        long nextAfterSeq = rows.isEmpty() ? request.afterSeq() : rows.getLast().seq();
        return new FinalResultWindow(request.taskId(), rows, nextAfterSeq, hasMore, state.finalRowsByMessageId.size());
    }

    @Override
    public synchronized Optional<FinalResultRow> getFinalResultByMessageId(String taskId, String messageId) {
        if (taskId == null || taskId.isBlank() || messageId == null || messageId.isBlank()) {
            return Optional.empty();
        }
        var state = tasks.get(taskId);
        if (state == null) {
            return Optional.empty();
        }
        purgeExpiredFinalRows(state, clock.getAsLong());
        return Optional.ofNullable(state.finalRowsByMessageId.get(messageId));
    }

    @Override
    public synchronized TaskRuntimeProgressSnapshot progressSnapshot(String taskId) {
        var state = tasks.get(taskId);
        if (state == null) {
            return TaskRuntimeProgressSnapshot.empty(taskId);
        }
        purgeExpiredFinalRows(state, clock.getAsLong());
        long success = 0L;
        long failed = 0L;
        long expired = 0L;
        for (var row : state.finalRowsByMessageId.values()) {
            if (row.success()) {
                success++;
            } else if (row.source() == ResultApplySource.LEASE_TIMEOUT) {
                expired++;
            } else {
                failed++;
            }
        }
        return new TaskRuntimeProgressSnapshot(
                taskId,
                state.ready.size() + state.delayed.size() + state.activeByMessageId.size()
                        + success + failed + expired,
                state.ready.size(),
                state.delayed.size(),
                state.activeByMessageId.size(),
                success,
                failed,
                expired);
    }

    @Override
    public synchronized DiscardTaskRuntimeOutcome discardTaskRuntime(DiscardTaskRuntimeCommand command) {
        var state = tasks.remove(command.taskId());
        dirtyTasks.remove(command.taskId());
        if (state == null) {
            return new DiscardTaskRuntimeOutcome(command.taskId(), 0L, 0L, 0L);
        }
        return new DiscardTaskRuntimeOutcome(
                command.taskId(),
                state.ready.size() + state.delayed.size(),
                state.activeByMessageId.size(),
                state.finalRowsByMessageId.size());
    }

    @Override
    public synchronized DiscardTaskWorkOutcome discardTaskWork(DiscardTaskWorkCommand command) {
        var state = tasks.get(command.taskId());
        dirtyTasks.remove(command.taskId());
        if (state == null) {
            return new DiscardTaskWorkOutcome(command.taskId(), 0L, 0L);
        }
        long readyCount = state.ready.size() + state.delayed.size();
        long activeCount = state.activeByMessageId.size();
        state.ready.clear();
        state.delayed.clear();
        state.activeByMessageId.clear();
        state.eligibility = null;
        if (state.finalRowsByMessageId.isEmpty()) {
            tasks.remove(command.taskId());
        }
        return new DiscardTaskWorkOutcome(command.taskId(), readyCount, activeCount);
    }

    private TaskState taskState(String taskId) {
        return tasks.computeIfAbsent(taskId, ignored -> new TaskState(RuntimeEpoch.of(taskId, 0L)));
    }

    private void promoteDueRetries(long nowMillis) {
        for (var entry : tasks.entrySet()) {
            var state = entry.getValue();
            var iterator = state.delayed.iterator();
            while (iterator.hasNext()) {
                var delayed = iterator.next();
                if (delayed.retryAtMillis() <= nowMillis) {
                    state.ready.addLast(delayed.ready());
                    dirtyTasks.add(entry.getKey());
                    iterator.remove();
                }
            }
        }
    }

    private boolean canRetry(ResultApplyCommand command) {
        return command.retryPolicy().maxRetryCount() > 0
                && command.attemptNo() <= command.retryPolicy().maxRetryCount();
    }

    private void addFinalRow(TaskState state,
                             ResultApplyCommand command,
                             ActiveItem active,
                             long finalizedAtMillis) {
        state.finalRowsByMessageId.put(command.messageId(), new FinalResultRow(
                command.taskId(),
                command.messageId(),
                state.nextFinalSeq++,
                command.attemptNo(),
                command.workerId(),
                active == null ? "" : active.reservation().batchId(),
                command.source(),
                command.success(),
                command.resultPayloadJson(),
                command.failureReason(),
                finalizedAtMillis,
                finalExpiresAt(command)));
    }

    private long finalExpiresAt(ResultApplyCommand command) {
        var retentionMillis = command.finalityPolicy().finalResultRetentionMillis();
        return retentionMillis <= 0 ? 0L : command.observedAtMillis() + retentionMillis;
    }

    private void purgeExpiredFinalRows(TaskState state, long nowMillis) {
        state.finalRowsByMessageId.values().removeIf(row -> row.expiresAtMillis() > 0 && row.expiresAtMillis() <= nowMillis);
    }

    private MessageFinalityOutcome rejected(ResultApplyCommand command, String reason) {
        return new MessageFinalityOutcome(
                MessageFinalityStatus.REJECTED,
                command.taskId(),
                command.messageId(),
                command.attemptNo(),
                false,
                false,
                0L,
                0L,
                reason);
    }

    private static ClaimedWorkItem toClaimed(ActiveItem active) {
        var ready = active.ready();
        var reservation = active.reservation();
        return new ClaimedWorkItem(
                ready.taskId(),
                ready.messageId(),
                ready.eventCode(),
                ready.payloadJson(),
                ready.payloadRef(),
                active.leaseToken(),
                reservation.reservationToken(),
                reservation.scoreBandClaimScore(),
                reservation.workerId(),
                reservation.workerGroupId(),
                reservation.batchId(),
                ready.attemptNo(),
                active.leaseExpireAtMillis());
    }

    private static ActiveLeaseRepairCandidate toRepairCandidate(ActiveItem active) {
        var ready = active.ready();
        return new ActiveLeaseRepairCandidate(
                ready.taskId(),
                ready.messageId(),
                active.leaseToken(),
                active.reservation().workerId(),
                active.reservation().workerGroupId(),
                active.reservation().batchId(),
                active.reservation().reservationToken(),
                active.reservation().scoreBandClaimScore(),
                ready.attemptNo(),
                active.leaseExpireAtMillis());
    }

    private static SchedulerEligibilityPolicy defaultEligibility() {
        return new SchedulerEligibilityPolicy(RuntimeGate.OPEN, "default", 0L, 0L, 0L, 0L);
    }

    private static boolean sameEpoch(RuntimeEpoch left, RuntimeEpoch right) {
        return left.taskId().equals(right.taskId())
                && left.epoch() == right.epoch()
                && Objects.equals(left.fenceToken(), right.fenceToken());
    }

    private static final class TaskState {
        private final ArrayDeque<ReadyItem> ready = new ArrayDeque<>();
        private final List<DelayedItem> delayed = new ArrayList<>();
        private final Map<String, ActiveItem> activeByMessageId = new LinkedHashMap<>();
        private final LinkedHashMap<String, FinalResultRow> finalRowsByMessageId = new LinkedHashMap<>();
        private long nextFinalSeq = 1L;
        private SchedulerEligibilityPolicy eligibility;
        private RuntimeEpoch runtimeEpoch;

        private TaskState(RuntimeEpoch runtimeEpoch) {
            this.runtimeEpoch = runtimeEpoch;
        }

        private boolean containsMessage(String messageId) {
            return ready.stream().anyMatch(item -> item.messageId().equals(messageId))
                    || delayed.stream().anyMatch(item -> item.ready().messageId().equals(messageId))
                    || activeByMessageId.containsKey(messageId)
                    || finalRowsByMessageId.containsKey(messageId);
        }

        private long pendingBacklogSize() {
            return ready.size() + delayed.size();
        }
    }

    private record ReadyItem(
            String taskId,
            String messageId,
            String eventCode,
            Map<String, Object> payloadJson,
            String payloadRef,
            RuntimeEpoch runtimeEpoch,
            int attemptNo
    ) {

        private static ReadyItem initial(
                String taskId,
                String messageId,
                String eventCode,
                Map<String, Object> payloadJson,
                String payloadRef,
                RuntimeEpoch runtimeEpoch
        ) {
            return new ReadyItem(taskId, messageId, eventCode, Map.copyOf(payloadJson), payloadRef, runtimeEpoch, 1);
        }

        private ReadyItem nextAttempt(RuntimeEpoch nextEpoch) {
            return new ReadyItem(taskId, messageId, eventCode, payloadJson, payloadRef, nextEpoch, attemptNo + 1);
        }
    }

    private record DelayedItem(ReadyItem ready, long retryAtMillis) {
    }

    private record ActiveItem(
            ReadyItem ready,
            WorkerReservationEvidence reservation,
            String leaseToken,
            long leaseExpireAtMillis
    ) {
    }
}
