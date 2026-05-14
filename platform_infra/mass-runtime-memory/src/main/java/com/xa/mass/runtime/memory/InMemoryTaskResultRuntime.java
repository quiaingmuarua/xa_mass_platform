package com.xa.mass.runtime.memory;

import com.xa.mass.runtime.api.BarrierClaim;
import com.xa.mass.runtime.api.BarrierMarkResult;
import com.xa.mass.runtime.api.CommitResult;
import com.xa.mass.runtime.api.StageResult;
import com.xa.mass.runtime.api.TaskResultCallbackDraft;
import com.xa.mass.runtime.api.TaskResultFinalDraft;
import com.xa.mass.runtime.api.TaskResultRepairCandidate;
import com.xa.mass.runtime.api.TaskResultRuntime;
import com.xa.mass.runtime.api.TaskResultRuntimeRow;
import com.xa.mass.runtime.api.TaskResultWindow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class InMemoryTaskResultRuntime implements TaskResultRuntime {

    private static final long DEFAULT_BARRIER_TTL_MILLIS = Long.getLong(
            "xa.mass.runtime.resultBarrierClaimTtlMillis", 30_000L);

    private final Map<String, TaskResultCallbackDraft> stagedById = new LinkedHashMap<>();
    private final Map<String, Set<String>> stageIdsByTask = new HashMap<>();
    private final Map<MessageKey, Set<String>> stageIdsByMessage = new HashMap<>();
    private final Map<ResultKey, TaskResultRuntimeRow> visibleByMessage = new HashMap<>();
    private final Map<String, TreeMap<Long, TaskResultRuntimeRow>> visibleByTaskSeq = new HashMap<>();
    private final Map<String, Long> nextSeqByTask = new HashMap<>();
    private final Map<BarrierKey, BarrierLeaseState> attemptClosedBarriers = new HashMap<>();
    private final Map<BarrierKey, BarrierLeaseState> logicalFinalBarriers = new HashMap<>();
    private final Map<BarrierKey, BarrierLeaseState> progressBarriers = new HashMap<>();
    private final TreeMap<PendingKey, Instant> attemptClosedPending = new TreeMap<>();
    private final TreeMap<PendingKey, Instant> logicalFinalPending = new TreeMap<>();
    private final TreeMap<PendingKey, Instant> progressPending = new TreeMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final long barrierClaimTtlMillis;

    public InMemoryTaskResultRuntime() {
        this(DEFAULT_BARRIER_TTL_MILLIS);
    }

    InMemoryTaskResultRuntime(long barrierClaimTtlMillis) {
        this.barrierClaimTtlMillis = Math.max(1L, barrierClaimTtlMillis);
    }

    @Override
    public synchronized StageResult stageCallback(TaskResultCallbackDraft draft) {
        if (!running.get()) {
            return StageResult.unavailable("result runtime is stopped");
        }
        if (draft == null) {
            return StageResult.rejected("draft must not be null");
        }
        TaskResultCallbackDraft existing = stagedById.get(draft.stageId());
        if (existing != null) {
            return StageResult.duplicate(existing);
        }
        stagedById.put(draft.stageId(), draft);
        stageIdsByTask.computeIfAbsent(draft.taskId(), ignored -> new LinkedHashSet<>()).add(draft.stageId());
        stageIdsByMessage.computeIfAbsent(new MessageKey(draft.taskId(), draft.messageId()), ignored -> new LinkedHashSet<>())
                .add(draft.stageId());
        return StageResult.staged(draft);
    }

    @Override
    public synchronized boolean discardStagedCallback(String stageId) {
        if (isBlank(stageId)) {
            return false;
        }
        TaskResultCallbackDraft removed = stagedById.remove(stageId);
        if (removed == null) {
            return false;
        }
        removeStageIndexes(removed);
        return true;
    }

    @Override
    public synchronized int discardStagedCallbacksForMessage(String taskId, String messageId) {
        if (isBlank(taskId) || isBlank(messageId)) {
            return 0;
        }
        MessageKey messageKey = new MessageKey(taskId, messageId);
        Set<String> stageIds = stageIdsByMessage.remove(messageKey);
        if (stageIds == null || stageIds.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (String stageId : new ArrayList<>(stageIds)) {
            TaskResultCallbackDraft draft = stagedById.remove(stageId);
            if (draft != null) {
                removeStageIndexes(draft);
                removed++;
            }
        }
        return removed;
    }

    @Override
    public synchronized CommitResult commitVisibleFinal(TaskResultFinalDraft finalDraft) {
        if (!running.get()) {
            return CommitResult.unavailable("result runtime is stopped");
        }
        if (finalDraft == null) {
            return CommitResult.rejected("finalDraft must not be null");
        }
        ResultKey key = new ResultKey(finalDraft.taskId(), finalDraft.messageId());
        TaskResultRuntimeRow existing = visibleByMessage.get(key);
        if (existing != null) {
            return CommitResult.duplicate(existing);
        }
        long seq = nextSeqByTask.merge(finalDraft.taskId(), 1L, Long::sum);
        TaskResultRuntimeRow row = rowFromDraft(finalDraft, seq, false, false, false);
        visibleByMessage.put(key, row);
        visibleByTaskSeq.computeIfAbsent(finalDraft.taskId(), ignored -> new TreeMap<>()).put(seq, row);
        addPending(row);
        return CommitResult.committed(row);
    }

    @Override
    public synchronized List<TaskResultRepairCandidate> scanRepairCandidates(int limit) {
        if (!running.get() || limit <= 0) {
            return List.of();
        }
        List<TaskResultRepairCandidate> candidates = new ArrayList<>(limit);
        for (TaskResultCallbackDraft draft : new ArrayList<>(stagedById.values())) {
            TaskResultRuntimeRow visible = visibleByMessage.get(new ResultKey(draft.taskId(), draft.messageId()));
            if (visible != null) {
                cleanupFullyConvergedStages(visible);
                continue;
            }
            candidates.add(TaskResultRepairCandidate.missingVisibleFinal(draft));
            if (candidates.size() >= limit) {
                return List.copyOf(candidates);
            }
        }
        collectPendingCandidates(candidates, attemptClosedPending, BarrierField.ATTEMPT_CLOSED, limit);
        collectPendingCandidates(candidates, logicalFinalPending, BarrierField.LOGICAL_FINAL, limit);
        collectPendingCandidates(candidates, progressPending, BarrierField.PROGRESS, limit);
        return List.copyOf(candidates);
    }

    @Override
    public synchronized BarrierClaim claimAttemptClosedPublish(String taskId, String messageId, long finalSeq) {
        return claimBarrier(attemptClosedBarriers, taskId, messageId, finalSeq, BarrierField.ATTEMPT_CLOSED);
    }

    @Override
    public synchronized BarrierMarkResult markAttemptClosedPublished(String taskId,
                                                                     String messageId,
                                                                     long finalSeq,
                                                                     String claimToken) {
        return markBarrier(attemptClosedBarriers, attemptClosedPending, taskId, messageId, finalSeq,
                claimToken, BarrierField.ATTEMPT_CLOSED);
    }

    @Override
    public synchronized BarrierClaim claimLogicalFinalPublish(String taskId, String messageId, long finalSeq) {
        return claimBarrier(logicalFinalBarriers, taskId, messageId, finalSeq, BarrierField.LOGICAL_FINAL);
    }

    @Override
    public synchronized BarrierMarkResult markLogicalFinalPublished(String taskId,
                                                                    String messageId,
                                                                    long finalSeq,
                                                                    String claimToken) {
        return markBarrier(logicalFinalBarriers, logicalFinalPending, taskId, messageId, finalSeq,
                claimToken, BarrierField.LOGICAL_FINAL);
    }

    @Override
    public synchronized BarrierClaim claimProgressApply(String taskId, String messageId, long finalSeq) {
        return claimBarrier(progressBarriers, taskId, messageId, finalSeq, BarrierField.PROGRESS);
    }

    @Override
    public synchronized BarrierMarkResult markProgressApplied(String taskId,
                                                              String messageId,
                                                              long finalSeq,
                                                              String claimToken) {
        return markBarrier(progressBarriers, progressPending, taskId, messageId, finalSeq,
                claimToken, BarrierField.PROGRESS);
    }

    @Override
    public synchronized TaskResultWindow readWindow(String taskId, long afterSeq, int limit) {
        if (isBlank(taskId)) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        int boundedLimit = Math.max(0, limit);
        if (!running.get() || boundedLimit == 0) {
            return new TaskResultWindow(taskId, List.of(), Math.max(0L, afterSeq), false, countVisibleResults(taskId));
        }
        TreeMap<Long, TaskResultRuntimeRow> rows = visibleByTaskSeq.get(taskId);
        if (rows == null || rows.isEmpty()) {
            return new TaskResultWindow(taskId, List.of(), Math.max(0L, afterSeq), false, 0L);
        }
        List<TaskResultRuntimeRow> items = rows.tailMap(Math.max(0L, afterSeq), false)
                .values()
                .stream()
                .limit(boundedLimit)
                .toList();
        long nextAfterSeq = items.isEmpty() ? Math.max(0L, afterSeq) : items.get(items.size() - 1).seq();
        boolean hasMore = rows.higherKey(nextAfterSeq) != null;
        return new TaskResultWindow(taskId, items, nextAfterSeq, hasMore, rows.size());
    }

    @Override
    public synchronized long countVisibleResults(String taskId) {
        TreeMap<Long, TaskResultRuntimeRow> rows = visibleByTaskSeq.get(taskId);
        return rows == null ? 0L : rows.size();
    }

    @Override
    public synchronized Optional<TaskResultRuntimeRow> getVisibleByMessageId(String taskId, String messageId) {
        if (isBlank(taskId) || isBlank(messageId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(visibleByMessage.get(new ResultKey(taskId, messageId)));
    }

    @Override
    public synchronized long discardTask(String taskId) {
        if (isBlank(taskId)) {
            return 0L;
        }
        long removed = 0L;
        Set<String> stageIds = stageIdsByTask.remove(taskId);
        if (stageIds != null) {
            for (String stageId : stageIds) {
                TaskResultCallbackDraft removedDraft = stagedById.remove(stageId);
                if (removedDraft != null) {
                    removeStageIndexes(removedDraft);
                    removed++;
                }
            }
        }
        stageIdsByMessage.keySet().removeIf(key -> taskId.equals(key.taskId));
        TreeMap<Long, TaskResultRuntimeRow> rows = visibleByTaskSeq.remove(taskId);
        if (rows != null) {
            for (TaskResultRuntimeRow row : rows.values()) {
                visibleByMessage.remove(new ResultKey(row.taskId(), row.messageId()));
                attemptClosedPending.remove(new PendingKey(row.taskId(), row.messageId(), row.seq()));
                logicalFinalPending.remove(new PendingKey(row.taskId(), row.messageId(), row.seq()));
                progressPending.remove(new PendingKey(row.taskId(), row.messageId(), row.seq()));
                removed++;
            }
        }
        nextSeqByTask.remove(taskId);
        attemptClosedBarriers.keySet().removeIf(key -> taskId.equals(key.taskId));
        logicalFinalBarriers.keySet().removeIf(key -> taskId.equals(key.taskId));
        progressBarriers.keySet().removeIf(key -> taskId.equals(key.taskId));
        return removed;
    }

    @Override
    public synchronized void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        stagedById.clear();
        stageIdsByTask.clear();
        stageIdsByMessage.clear();
        visibleByMessage.clear();
        visibleByTaskSeq.clear();
        nextSeqByTask.clear();
        attemptClosedBarriers.clear();
        logicalFinalBarriers.clear();
        progressBarriers.clear();
        attemptClosedPending.clear();
        logicalFinalPending.clear();
        progressPending.clear();
    }

    synchronized int countMessageStageIds(String taskId, String messageId) {
        Set<String> stageIds = stageIdsByMessage.get(new MessageKey(taskId, messageId));
        return stageIds == null ? 0 : stageIds.size();
    }

    private void collectPendingCandidates(List<TaskResultRepairCandidate> candidates,
                                          TreeMap<PendingKey, Instant> pendingIndex,
                                          BarrierField field,
                                          int limit) {
        if (candidates.size() >= limit) {
            return;
        }
        List<PendingKey> staleKeys = new ArrayList<>();
        for (Map.Entry<PendingKey, Instant> entry : pendingIndex.entrySet()) {
            PendingKey key = entry.getKey();
            TaskResultRuntimeRow row = visibleByMessage.get(new ResultKey(key.taskId, key.messageId));
            if (row == null || row.seq() != key.seq) {
                staleKeys.add(key);
                continue;
            }
            if (isBarrierDone(row, field)) {
                staleKeys.add(key);
                continue;
            }
            candidates.add(candidateFor(row, field));
            if (candidates.size() >= limit) {
                break;
            }
        }
        staleKeys.forEach(pendingIndex::remove);
    }

    private BarrierClaim claimBarrier(Map<BarrierKey, BarrierLeaseState> barriers,
                                      String taskId,
                                      String messageId,
                                      long finalSeq,
                                      BarrierField field) {
        if (!running.get()) {
            return BarrierClaim.unavailable();
        }
        if (isBlank(taskId) || isBlank(messageId) || finalSeq <= 0) {
            return BarrierClaim.rejected();
        }
        TaskResultRuntimeRow row = visibleByMessage.get(new ResultKey(taskId, messageId));
        if (row == null || row.seq() != finalSeq) {
            return BarrierClaim.rejected();
        }
        if (isBarrierDone(row, field)) {
            return BarrierClaim.alreadyDone();
        }
        BarrierKey key = new BarrierKey(taskId, messageId, finalSeq);
        Instant now = Instant.now();
        BarrierLeaseState existing = barriers.get(key);
        if (existing != null && existing.done) {
            return BarrierClaim.alreadyDone();
        }
        if (existing != null && existing.expiresAt != null && existing.expiresAt.isAfter(now)) {
            return BarrierClaim.busy(existing.claimToken, existing.claimedAt, existing.expiresAt);
        }
        String claimToken = UUID.randomUUID().toString();
        Instant expiresAt = now.plusMillis(barrierClaimTtlMillis);
        barriers.put(key, new BarrierLeaseState(claimToken, now, expiresAt, false));
        return BarrierClaim.claimed(claimToken, now, expiresAt);
    }

    private BarrierMarkResult markBarrier(Map<BarrierKey, BarrierLeaseState> barriers,
                                          TreeMap<PendingKey, Instant> pendingIndex,
                                          String taskId,
                                          String messageId,
                                          long finalSeq,
                                          String claimToken,
                                          BarrierField field) {
        if (!running.get()) {
            return BarrierMarkResult.unavailable("result runtime is stopped");
        }
        if (isBlank(taskId) || isBlank(messageId) || finalSeq <= 0) {
            return BarrierMarkResult.rejected("taskId, messageId, and finalSeq are required");
        }
        ResultKey resultKey = new ResultKey(taskId, messageId);
        TaskResultRuntimeRow row = visibleByMessage.get(resultKey);
        if (row == null || row.seq() != finalSeq) {
            return BarrierMarkResult.rejected("visible row not found");
        }
        if (isBarrierDone(row, field)) {
            return BarrierMarkResult.alreadyDone();
        }
        BarrierKey barrierKey = new BarrierKey(taskId, messageId, finalSeq);
        BarrierLeaseState state = barriers.get(barrierKey);
        if (state == null) {
            return BarrierMarkResult.tokenMismatch("no active barrier claim");
        }
        if (state.done) {
            return BarrierMarkResult.alreadyDone();
        }
        if (isBlank(claimToken) || !claimToken.equals(state.claimToken)) {
            return BarrierMarkResult.tokenMismatch("claim token mismatch");
        }
        TaskResultRuntimeRow updated = markBarrierDone(row, field);
        visibleByMessage.put(resultKey, updated);
        TreeMap<Long, TaskResultRuntimeRow> rows = visibleByTaskSeq.get(taskId);
        if (rows != null) {
            rows.put(finalSeq, updated);
        }
        barriers.put(barrierKey, state.asDone());
        pendingIndex.remove(new PendingKey(taskId, messageId, finalSeq));
        return BarrierMarkResult.marked();
    }

    private void addPending(TaskResultRuntimeRow row) {
        PendingKey pendingKey = new PendingKey(row.taskId(), row.messageId(), row.seq());
        Instant observedAt = row.updateTime() == null ? Instant.now() : row.updateTime();
        attemptClosedPending.put(pendingKey, observedAt);
        logicalFinalPending.put(pendingKey, observedAt);
        progressPending.put(pendingKey, observedAt);
    }

    private TaskResultRuntimeRow rowFromDraft(TaskResultFinalDraft draft,
                                              long seq,
                                              boolean attemptClosedPublished,
                                              boolean logicalFinalPublished,
                                              boolean progressApplied) {
        return new TaskResultRuntimeRow(
                draft.taskId(),
                draft.messageId(),
                seq,
                draft.eventCode(),
                draft.status(),
                draft.finalReason(),
                Math.max(0, draft.retryCount()),
                Math.max(0, draft.maxRetryCount()),
                draft.workerId(),
                draft.workerContextId(),
                draft.batchId(),
                draft.attemptId(),
                draft.payloadRef(),
                draft.createTime(),
                draft.assignedTime(),
                draft.startTime(),
                draft.completeTime(),
                draft.updateTime(),
                draft.errorCode(),
                draft.errorMessage(),
                draft.output(),
                attemptClosedPublished,
                logicalFinalPublished,
                progressApplied
        );
    }

    private void cleanupFullyConvergedStages(TaskResultRuntimeRow row) {
        if (row != null && row.attemptClosedPublished() && row.logicalFinalPublished() && row.progressApplied()) {
            discardStagedCallbacksForMessage(row.taskId(), row.messageId());
        }
    }

    private static boolean isBarrierDone(TaskResultRuntimeRow row, BarrierField field) {
        return switch (field) {
            case ATTEMPT_CLOSED -> row.attemptClosedPublished();
            case LOGICAL_FINAL -> row.logicalFinalPublished();
            case PROGRESS -> row.progressApplied();
        };
    }

    private static TaskResultRuntimeRow markBarrierDone(TaskResultRuntimeRow row, BarrierField field) {
        return switch (field) {
            case ATTEMPT_CLOSED -> row.withAttemptClosedPublished();
            case LOGICAL_FINAL -> row.withLogicalFinalPublished();
            case PROGRESS -> row.withProgressApplied();
        };
    }

    private static TaskResultRepairCandidate candidateFor(TaskResultRuntimeRow row, BarrierField field) {
        return switch (field) {
            case ATTEMPT_CLOSED -> TaskResultRepairCandidate.missingAttemptClosedPublish(row);
            case LOGICAL_FINAL -> TaskResultRepairCandidate.missingLogicalFinalPublish(row);
            case PROGRESS -> TaskResultRepairCandidate.missingProgressApply(row);
        };
    }

    private void removeStageIndexes(TaskResultCallbackDraft draft) {
        Set<String> taskStages = stageIdsByTask.get(draft.taskId());
        if (taskStages != null) {
            taskStages.remove(draft.stageId());
            if (taskStages.isEmpty()) {
                stageIdsByTask.remove(draft.taskId());
            }
        }
        MessageKey messageKey = new MessageKey(draft.taskId(), draft.messageId());
        Set<String> messageStages = stageIdsByMessage.get(messageKey);
        if (messageStages != null) {
            messageStages.remove(draft.stageId());
            if (messageStages.isEmpty()) {
                stageIdsByMessage.remove(messageKey);
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ResultKey(String taskId, String messageId) {
        private ResultKey {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(messageId, "messageId");
        }
    }

    private record MessageKey(String taskId, String messageId) implements Comparable<MessageKey> {
        @Override
        public int compareTo(MessageKey other) {
            int taskCompare = taskId.compareTo(other.taskId);
            return taskCompare != 0 ? taskCompare : messageId.compareTo(other.messageId);
        }
    }

    private record PendingKey(String taskId, String messageId, long seq) implements Comparable<PendingKey> {
        @Override
        public int compareTo(PendingKey other) {
            int taskCompare = taskId.compareTo(other.taskId);
            if (taskCompare != 0) {
                return taskCompare;
            }
            int messageCompare = messageId.compareTo(other.messageId);
            if (messageCompare != 0) {
                return messageCompare;
            }
            return Long.compare(seq, other.seq);
        }
    }

    private record BarrierKey(String taskId, String messageId, long seq) {
    }

    private enum BarrierField {
        ATTEMPT_CLOSED,
        LOGICAL_FINAL,
        PROGRESS
    }

    private record BarrierLeaseState(String claimToken, Instant claimedAt, Instant expiresAt, boolean done) {
        private BarrierLeaseState asDone() {
            return new BarrierLeaseState(claimToken, claimedAt, expiresAt, true);
        }
    }
}
