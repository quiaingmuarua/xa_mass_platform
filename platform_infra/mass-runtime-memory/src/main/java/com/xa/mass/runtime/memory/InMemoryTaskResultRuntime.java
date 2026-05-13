package com.xa.mass.runtime.memory;

import com.xa.mass.runtime.api.BarrierClaim;
import com.xa.mass.runtime.api.CommitResult;
import com.xa.mass.runtime.api.StageResult;
import com.xa.mass.runtime.api.TaskResultCallbackDraft;
import com.xa.mass.runtime.api.TaskResultFinalDraft;
import com.xa.mass.runtime.api.TaskResultRepairCandidate;
import com.xa.mass.runtime.api.TaskResultRetentionPolicy;
import com.xa.mass.runtime.api.TaskResultRuntime;
import com.xa.mass.runtime.api.TaskResultRuntimeRow;
import com.xa.mass.runtime.api.TaskResultWindow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class InMemoryTaskResultRuntime implements TaskResultRuntime {

    private final Map<String, TaskResultCallbackDraft> stagedById = new LinkedHashMap<>();
    private final Map<String, Set<String>> stageIdsByTask = new HashMap<>();
    private final Map<ResultKey, TaskResultRuntimeRow> visibleByMessage = new HashMap<>();
    private final Map<String, TreeMap<Long, TaskResultRuntimeRow>> visibleByTaskSeq = new HashMap<>();
    private final Map<String, Long> nextSeqByTask = new HashMap<>();
    private final Map<BarrierKey, BarrierState> logicalFinalBarriers = new HashMap<>();
    private final Map<BarrierKey, BarrierState> progressBarriers = new HashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);

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
        stageIdsByTask.computeIfAbsent(draft.taskId(), ignored -> new java.util.LinkedHashSet<>())
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
        Set<String> stageIds = stageIdsByTask.get(removed.taskId());
        if (stageIds != null) {
            stageIds.remove(stageId);
            if (stageIds.isEmpty()) {
                stageIdsByTask.remove(removed.taskId());
            }
        }
        return true;
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
        TaskResultRuntimeRow row = rowFromDraft(finalDraft, seq, false, false);
        visibleByMessage.put(key, row);
        visibleByTaskSeq.computeIfAbsent(finalDraft.taskId(), ignored -> new TreeMap<>()).put(seq, row);
        return CommitResult.committed(row);
    }

    @Override
    public synchronized List<TaskResultRepairCandidate> scanRepairCandidates(int limit) {
        if (!running.get() || limit <= 0) {
            return List.of();
        }
        List<TaskResultRepairCandidate> candidates = new ArrayList<>(Math.min(limit, stagedById.size()));
        for (TaskResultCallbackDraft draft : stagedById.values()) {
            if (visibleByMessage.containsKey(new ResultKey(draft.taskId(), draft.messageId()))) {
                continue;
            }
            candidates.add(new TaskResultRepairCandidate(draft));
            if (candidates.size() >= limit) {
                break;
            }
        }
        return List.copyOf(candidates);
    }

    @Override
    public synchronized CommitResult repairVisibleFinal(TaskResultRepairCandidate candidate) {
        if (candidate == null || candidate.draft() == null) {
            return CommitResult.rejected("candidate must not be null");
        }
        TaskResultCallbackDraft draft = candidate.draft();
        Instant now = Instant.now();
        String status = draft.success() ? "SUCCESS" : "FAILED";
        String finalReason = draft.success() ? "BUSINESS_SUCCESS" : "BUSINESS_FAILED";
        return commitVisibleFinal(new TaskResultFinalDraft(
                draft.taskId(),
                draft.messageId(),
                draft.eventCode(),
                status,
                finalReason,
                draft.retryCount(),
                draft.maxRetryCount(),
                draft.workerId(),
                draft.workerContextId(),
                draft.batchId(),
                draft.attemptId(),
                draft.payloadRef(),
                draft.createTime() != null ? draft.createTime() : draft.receivedAt(),
                draft.leasedAt(),
                draft.leasedAt(),
                now,
                now,
                draft.errorCode(),
                draft.detail(),
                draft.output(),
                draft.stageId()
        ));
    }

    @Override
    public synchronized BarrierClaim claimLogicalFinalPublish(String taskId, String messageId, long finalSeq) {
        return claimBarrier(logicalFinalBarriers, taskId, messageId, finalSeq, true);
    }

    @Override
    public synchronized void markLogicalFinalPublished(String taskId, String messageId, long finalSeq) {
        markBarrier(logicalFinalBarriers, taskId, messageId, finalSeq, true);
    }

    @Override
    public synchronized BarrierClaim claimProgressApply(String taskId, String messageId, long finalSeq) {
        return claimBarrier(progressBarriers, taskId, messageId, finalSeq, false);
    }

    @Override
    public synchronized void markProgressApplied(String taskId, String messageId, long finalSeq) {
        markBarrier(progressBarriers, taskId, messageId, finalSeq, false);
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
    public synchronized long compactTerminalTask(String taskId, TaskResultRetentionPolicy policy) {
        if (isBlank(taskId) || policy == null || policy.keepLatestRows() == Long.MAX_VALUE) {
            return 0L;
        }
        TreeMap<Long, TaskResultRuntimeRow> rows = visibleByTaskSeq.get(taskId);
        if (rows == null || rows.size() <= policy.keepLatestRows()) {
            return 0L;
        }
        long removeCount = rows.size() - policy.keepLatestRows();
        List<Long> toRemove = rows.keySet().stream()
                .sorted(Comparator.naturalOrder())
                .limit(removeCount)
                .toList();
        for (Long seq : toRemove) {
            TaskResultRuntimeRow row = rows.remove(seq);
            if (row != null) {
                visibleByMessage.remove(new ResultKey(row.taskId(), row.messageId()));
            }
        }
        return toRemove.size();
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
                if (stagedById.remove(stageId) != null) {
                    removed++;
                }
            }
        }
        TreeMap<Long, TaskResultRuntimeRow> rows = visibleByTaskSeq.remove(taskId);
        if (rows != null) {
            for (TaskResultRuntimeRow row : rows.values()) {
                visibleByMessage.remove(new ResultKey(row.taskId(), row.messageId()));
                removed++;
            }
        }
        nextSeqByTask.remove(taskId);
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
        visibleByMessage.clear();
        visibleByTaskSeq.clear();
        nextSeqByTask.clear();
        logicalFinalBarriers.clear();
        progressBarriers.clear();
    }

    private BarrierClaim claimBarrier(Map<BarrierKey, BarrierState> barriers,
                                      String taskId,
                                      String messageId,
                                      long finalSeq,
                                      boolean logicalFinal) {
        if (!running.get() || isBlank(taskId) || isBlank(messageId) || finalSeq <= 0) {
            return BarrierClaim.rejected();
        }
        TaskResultRuntimeRow row = visibleByMessage.get(new ResultKey(taskId, messageId));
        if (row == null || row.seq() != finalSeq) {
            return BarrierClaim.rejected();
        }
        if ((logicalFinal && row.logicalFinalPublished()) || (!logicalFinal && row.progressApplied())) {
            return BarrierClaim.alreadyDone();
        }
        BarrierKey key = new BarrierKey(taskId, messageId, finalSeq);
        BarrierState state = barriers.get(key);
        if (state == BarrierState.DONE) {
            return BarrierClaim.alreadyDone();
        }
        if (state == BarrierState.CLAIMED) {
            return BarrierClaim.busy();
        }
        barriers.put(key, BarrierState.CLAIMED);
        return BarrierClaim.claimed();
    }

    private void markBarrier(Map<BarrierKey, BarrierState> barriers,
                             String taskId,
                             String messageId,
                             long finalSeq,
                             boolean logicalFinal) {
        if (isBlank(taskId) || isBlank(messageId) || finalSeq <= 0) {
            return;
        }
        BarrierKey barrierKey = new BarrierKey(taskId, messageId, finalSeq);
        barriers.put(barrierKey, BarrierState.DONE);
        ResultKey resultKey = new ResultKey(taskId, messageId);
        TaskResultRuntimeRow row = visibleByMessage.get(resultKey);
        if (row == null || row.seq() != finalSeq) {
            return;
        }
        TaskResultRuntimeRow updated = logicalFinal
                ? row.withLogicalFinalPublished()
                : row.withProgressApplied();
        visibleByMessage.put(resultKey, updated);
        TreeMap<Long, TaskResultRuntimeRow> rows = visibleByTaskSeq.get(taskId);
        if (rows != null) {
            rows.put(finalSeq, updated);
        }
    }

    private TaskResultRuntimeRow rowFromDraft(TaskResultFinalDraft draft,
                                              long seq,
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
                logicalFinalPublished,
                progressApplied
        );
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

    private record BarrierKey(String taskId, String messageId, long seq) {
    }

    private enum BarrierState {
        CLAIMED,
        DONE
    }
}
