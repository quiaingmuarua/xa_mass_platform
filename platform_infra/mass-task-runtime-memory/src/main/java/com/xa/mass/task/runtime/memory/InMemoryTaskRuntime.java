package com.xa.mass.task.runtime.memory;

import com.xa.mass.task.runtime.ActiveLeaseRepairCandidate;
import com.xa.mass.task.runtime.ActiveTaskWorkSnapshot;
import com.xa.mass.task.runtime.AppendBatchOutcome;
import com.xa.mass.task.runtime.AppendItemInput;
import com.xa.mass.task.runtime.ClaimReadyOutcome;
import com.xa.mass.task.runtime.ClaimedWorkItem;
import com.xa.mass.task.runtime.FinalResultReadRequest;
import com.xa.mass.task.runtime.FinalResultRow;
import com.xa.mass.task.runtime.FinalResultWindow;
import com.xa.mass.task.runtime.MessageFinalityOutcome;
import com.xa.mass.task.runtime.MessageFinalityStatus;
import com.xa.mass.task.runtime.ResultApplySource;
import com.xa.mass.task.runtime.ResultCorrelationSnapshot;
import com.xa.mass.task.runtime.RuntimeResultFact;
import com.xa.mass.task.runtime.RetryMode;
import com.xa.mass.task.runtime.RuntimeEpoch;
import com.xa.mass.task.runtime.RuntimeGate;
import com.xa.mass.task.runtime.ScoreCandidate;
import com.xa.mass.task.runtime.ScoreCandidateBatch;
import com.xa.mass.task.runtime.SchedulerEligibilityPolicy;
import com.xa.mass.task.runtime.TaskRuntimeConvergencePort;
import com.xa.mass.task.runtime.TaskRuntimeProgressSnapshot;
import com.xa.mass.task.runtime.TaskRuntimeReadPort;
import com.xa.mass.task.runtime.TaskRuntimeResultWindowReadModel;
import com.xa.mass.task.runtime.TaskRuntimeMetaV1;
import com.xa.mass.task.runtime.TaskRuntimeResultPolicyV1;
import com.xa.mass.task.runtime.TaskRuntimeScorePort;
import com.xa.mass.task.runtime.TaskRuntimeWorkPort;
import com.xa.mass.task.runtime.TaskScoreV1;
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

public final class InMemoryTaskRuntime implements TaskRuntimeWorkPort,
        TaskRuntimeScorePort,
        TaskRuntimeConvergencePort,
        TaskRuntimeReadPort,
        TaskRuntimeResultWindowReadModel {

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
    public synchronized AppendBatchOutcome appendBacklog(String taskId, List<AppendItemInput> frames, int maxBatchSize) {
        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("frames must be non-empty");
        }
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be positive");
        }
        if (frames.size() > maxBatchSize) {
            return AppendBatchOutcome.rejectedBeforeRuntime(taskId, "items exceed maxAppendBatchSize");
        }
        var state = taskState(taskId);
        var messageIds = new ArrayList<String>();
        for (var frame : frames) {
            state.ready.addLast(ReadyItem.initial(
                    taskId,
                    frame.messageId(),
                    frame.eventCode(),
                    frame.payloadJson(),
                    frame.payloadRef(),
                    state.runtimeEpoch));
            messageIds.add(frame.messageId());
        }
        dirtyTasks.add(taskId);
        return AppendBatchOutcome.allAccepted(taskId, messageIds);
    }

    @Override
    public synchronized ClaimReadyOutcome claimBacklog(ScoreCandidate candidate,
                                                       List<WorkerReservationEvidence> reservations,
                                                       int maxItems,
                                                       long leaseMillis,
                                                       long nowMillis) {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate is required");
        }
        if (reservations == null || reservations.isEmpty()) {
            throw new IllegalArgumentException("reservations must be non-empty");
        }
        if (!candidate.observedScore().isDueAt(nowMillis)) {
            return new ClaimReadyOutcome(candidate.taskId(), List.of(), "score candidate is not dispatch-visible");
        }
        var state = tasks.get(candidate.taskId());
        if (state == null || state.ready.isEmpty()) {
            return new ClaimReadyOutcome(candidate.taskId(), List.of(), "no ready work");
        }
        var eligibility = state.eligibility;
        if (eligibility == null
                || !Objects.equals(eligibility.dispatchLane(), candidate.laneKey())) {
            return new ClaimReadyOutcome(candidate.taskId(), List.of(), "score candidate metadata mismatch");
        }
        var currentScore = new TaskScoreV1(eligibility.nextEligibleAtMillis());
        if (!currentScore.isDueAt(nowMillis) || currentScore.score() != candidate.observedScore().score()) {
            return new ClaimReadyOutcome(candidate.taskId(), List.of(), "score candidate mismatch");
        }
        if (!sameEpoch(state.runtimeEpoch, candidate.runtimeEpoch())) {
            return new ClaimReadyOutcome(candidate.taskId(), List.of(), "runtime epoch mismatch");
        }
        var claimLimit = Math.min(Math.max(1, maxItems), state.ready.size());
        var claimed = new ArrayList<ClaimedWorkItem>();
        for (int index = 0; index < claimLimit && !state.ready.isEmpty(); index++) {
            var ready = state.ready.removeFirst().withRuntimeEpoch(candidate.runtimeEpoch());
            var reservation = reservations.get(index % reservations.size());
            var leaseToken = UUID.randomUUID().toString();
            var leaseExpireAtMillis = nowMillis + Math.max(1L, leaseMillis);
            var active = new ActiveItem(ready, reservation, leaseToken, leaseExpireAtMillis);
            state.activeByMessageId.put(ready.messageId(), active);
            claimed.add(toClaimed(active));
        }
        if (state.ready.isEmpty()) {
            dirtyTasks.remove(candidate.taskId());
        }
        return new ClaimReadyOutcome(candidate.taskId(), claimed, "");
    }

    @Override
    public synchronized void putRuntimeMeta(TaskRuntimeMetaV1 meta) {
        var state = taskState(meta.taskId());
        state.resultPolicy = meta.resultPolicy();
        state.eligibility = meta.toEligibilityPolicy();
        state.runtimeEpoch = meta.runtimeEpoch();
        dirtyTasks.add(meta.taskId());
    }

    @Override
    public synchronized void seedNonSchedulable(String taskId, String laneKey, RuntimeEpoch epoch) {
        writeScore(taskId, laneKey, epoch, TaskScoreV1.createdNonSchedulable(), RuntimeGate.BLOCKED);
    }

    @Override
    public synchronized void markDispatchDue(String taskId, String laneKey, RuntimeEpoch epoch, long nowMillis) {
        writeScore(taskId, laneKey, epoch, TaskScoreV1.dueAt(nowMillis), RuntimeGate.OPEN);
    }

    @Override
    public synchronized void markSchedulerHold(String taskId, String laneKey, RuntimeEpoch epoch) {
        writeScore(taskId, laneKey, epoch, TaskScoreV1.schedulerHold(), RuntimeGate.PAUSED);
    }

    @Override
    public synchronized void markTerminalRetained(String taskId, String laneKey, RuntimeEpoch epoch) {
        writeScore(taskId, laneKey, epoch, TaskScoreV1.terminalClosed(), RuntimeGate.TERMINAL);
    }

    @Override
    public synchronized Optional<ScoreCandidate> scoreCandidate(String taskId, String laneKey) {
        var state = tasks.get(taskId);
        if (state == null || state.eligibility == null
                || !Objects.equals(state.eligibility.dispatchLane(), laneKey)) {
            return Optional.empty();
        }
        var score = new TaskScoreV1(state.eligibility.nextEligibleAtMillis());
        if (!score.isSchedulableTimeBand()) {
            return Optional.empty();
        }
        return Optional.of(new ScoreCandidate(
                taskId,
                laneKey,
                state.runtimeEpoch,
                score));
    }

    @Override
    public synchronized ScoreCandidateBatch discoverSchedulable(String laneKey, long maxScore, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        promoteDueRetries(maxScore);
        var candidates = new ArrayList<ScoreCandidate>();
        for (var entry : tasks.entrySet()) {
            if (candidates.size() >= limit) {
                break;
            }
            var taskId = entry.getKey();
            var state = entry.getValue();
            var eligibility = state.eligibility;
            if (eligibility == null) {
                continue;
            }
            var score = new TaskScoreV1(eligibility.nextEligibleAtMillis());
            if (!score.isDueAt(maxScore)
                    || !Objects.equals(eligibility.dispatchLane(), laneKey)) {
                continue;
            }
            candidates.add(new ScoreCandidate(
                    taskId,
                    laneKey,
                    state.runtimeEpoch,
                    score));
        }
        return new ScoreCandidateBatch(candidates);
    }

    @Override
    public synchronized List<String> promoteDueRetries(String laneKey,
                                                       long nowMillis,
                                                       int taskLimit,
                                                       int itemLimit) {
        return List.of();
    }

    @Override
    public synchronized List<ActiveLeaseRepairCandidate> scanExpiredLeases(String laneKey,
                                                                           long nowMillis,
                                                                           int taskLimit,
                                                                           int itemLimit) {
        return expiredActiveLeases(Math.max(1, itemLimit), nowMillis);
    }

    @Override
    public synchronized MessageFinalityOutcome applyResult(RuntimeResultFact fact) {
        TaskState state = tasks.get(fact.taskId());
        TaskRuntimeResultPolicyV1 policy = state == null
                ? TaskRuntimeResultPolicyV1.defaultPolicy()
                : state.resultPolicy;
        return applyResult(fact, policy);
    }

    @Override
    public synchronized boolean closeIfDrained(String taskId, String laneKey, RuntimeEpoch epoch) {
        var state = tasks.get(taskId);
        if (state == null
                || !state.ready.isEmpty()
                || !state.delayed.isEmpty()
                || !state.activeByMessageId.isEmpty()) {
            return false;
        }
        markTerminalRetained(taskId, laneKey, epoch);
        return true;
    }

    @Override
    public synchronized void discardRuntime(String taskId,
                                            String laneKey,
                                            RuntimeEpoch epoch,
                                            String reason) {
        tasks.remove(taskId);
        dirtyTasks.remove(taskId);
    }

    @Override
    public synchronized void discardWork(String taskId, RuntimeEpoch epoch, String reason) {
        var state = tasks.get(taskId);
        dirtyTasks.remove(taskId);
        if (state == null) {
            return;
        }
        state.ready.clear();
        state.delayed.clear();
        state.activeByMessageId.clear();
        state.eligibility = null;
        if (state.finalRowsByMessageId.isEmpty()) {
            tasks.remove(taskId);
        }
    }

    private MessageFinalityOutcome applyResult(RuntimeResultFact fact, TaskRuntimeResultPolicyV1 policy) {
        var state = tasks.get(fact.taskId());
        if (state == null) {
            return rejected(fact, "task runtime state not found");
        }
        var active = state.activeByMessageId.get(fact.messageId());
        if (active == null) {
            return state.finalRowsByMessageId.containsKey(fact.messageId())
                    ? MessageFinalityOutcome.duplicateOrLate(
                    fact.taskId(), fact.messageId(), fact.attemptNo(), "already final")
                    : rejected(fact, "active lease not found");
        }
        if (!active.leaseToken().equals(fact.leaseToken())
                || !active.reservation().workerId().equals(fact.workerId())
                || active.ready().attemptNo() != fact.attemptNo()) {
            return rejected(fact, "active lease correlation mismatch");
        }
        state.activeByMessageId.remove(fact.messageId());
        if (fact.success()) {
            addFinalRow(state, fact, active, fact.observedAtMillis(), policy);
            return MessageFinalityOutcome.logicalFinal(
                    fact.taskId(),
                    fact.messageId(),
                    fact.attemptNo(),
                    finalExpiresAt(policy, fact.observedAtMillis()));
        }
        if (canRetry(policy, fact.attemptNo())) {
            var retryAtMillis = fact.observedAtMillis() + policy.retryDelayMillis();
            var retry = active.ready().nextAttempt(fact.runtimeEpoch());
            if (policy.retryMode() == RetryMode.DUE_TIME && retryAtMillis > fact.observedAtMillis()) {
                state.delayed.add(new DelayedItem(retry, retryAtMillis));
            } else {
                state.ready.addLast(retry);
                dirtyTasks.add(fact.taskId());
            }
            return MessageFinalityOutcome.retryScheduled(
                    fact.taskId(),
                    fact.messageId(),
                    fact.attemptNo(),
                    retryAtMillis,
                    fact.failureReason());
        }
        addFinalRow(state, fact, active, fact.observedAtMillis(), policy);
        return MessageFinalityOutcome.logicalFinal(
                fact.taskId(),
                fact.messageId(),
                fact.attemptNo(),
                finalExpiresAt(policy, fact.observedAtMillis()));
    }

    @Override
    public synchronized ResultCorrelationSnapshot resultCorrelation(String taskId, String messageId) {
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

    private List<ActiveLeaseRepairCandidate> expiredActiveLeases(int limit, long nowMillis) {
        var candidates = new ArrayList<ActiveLeaseRepairCandidate>();
        for (var state : tasks.values()) {
            for (var active : state.activeByMessageId.values()) {
                if (candidates.size() >= limit) {
                    return candidates;
                }
                if (active.leaseExpireAtMillis() <= nowMillis) {
                    candidates.add(toRepairCandidate(active));
                }
            }
        }
        return candidates;
    }

    @Override
    public synchronized ActiveTaskWorkSnapshot activeWorkForTask(String taskId, int limit) {
        var state = tasks.get(taskId);
        if (state == null || state.activeByMessageId.isEmpty()) {
            return new ActiveTaskWorkSnapshot(taskId, List.of());
        }
        var activeItems = new ArrayList<ActiveLeaseRepairCandidate>();
        for (var active : state.activeByMessageId.values()) {
            if (activeItems.size() >= Math.max(1, limit)) {
                break;
            }
            activeItems.add(toRepairCandidate(active));
        }
        return new ActiveTaskWorkSnapshot(taskId, activeItems);
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

    private TaskState taskState(String taskId) {
        return tasks.computeIfAbsent(taskId, ignored -> new TaskState(RuntimeEpoch.of(taskId, 0L)));
    }

    private void writeScore(String taskId,
                            String laneKey,
                            RuntimeEpoch epoch,
                            TaskScoreV1 score,
                            RuntimeGate projectionGate) {
        TaskRuntimeResultPolicyV1 resultPolicy = taskState(taskId).resultPolicy;
        putRuntimeMeta(new TaskRuntimeMetaV1(
                taskId,
                laneKey,
                projectionGate,
                epoch,
                score.score(),
                0L,
                0L,
                0L,
                resultPolicy));
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

    private boolean canRetry(TaskRuntimeResultPolicyV1 policy, int attemptNo) {
        return policy.maxRetryCount() > 0 && attemptNo <= policy.maxRetryCount();
    }

    private void addFinalRow(TaskState state,
                             RuntimeResultFact fact,
                             ActiveItem active,
                             long finalizedAtMillis,
                             TaskRuntimeResultPolicyV1 policy) {
        state.finalRowsByMessageId.put(fact.messageId(), new FinalResultRow(
                fact.taskId(),
                fact.messageId(),
                state.nextFinalSeq++,
                fact.attemptNo(),
                fact.workerId(),
                active == null ? "" : active.reservation().batchId(),
                fact.source(),
                fact.success(),
                fact.resultPayloadJson(),
                fact.failureReason(),
                finalizedAtMillis,
                finalExpiresAt(policy, fact.observedAtMillis())));
    }

    private long finalExpiresAt(TaskRuntimeResultPolicyV1 policy, long observedAtMillis) {
        var retentionMillis = policy.finalResultRetentionMillis();
        return retentionMillis <= 0 ? 0L : observedAtMillis + retentionMillis;
    }

    private void purgeExpiredFinalRows(TaskState state, long nowMillis) {
        state.finalRowsByMessageId.values().removeIf(row -> row.expiresAtMillis() > 0 && row.expiresAtMillis() <= nowMillis);
    }

    private MessageFinalityOutcome rejected(RuntimeResultFact fact, String reason) {
        return new MessageFinalityOutcome(
                MessageFinalityStatus.REJECTED,
                fact.taskId(),
                fact.messageId(),
                fact.attemptNo(),
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
        return new SchedulerEligibilityPolicy(
                RuntimeGate.OPEN,
                "default",
                TaskScoreV1.TIME_SCORE_FLOOR,
                0L,
                0L,
                0L);
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
        private TaskRuntimeResultPolicyV1 resultPolicy = TaskRuntimeResultPolicyV1.defaultPolicy();

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

        private ReadyItem withRuntimeEpoch(RuntimeEpoch nextEpoch) {
            return new ReadyItem(taskId, messageId, eventCode, payloadJson, payloadRef, nextEpoch, attemptNo);
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
