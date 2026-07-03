package com.xa.mass.task.runtime.starter;

import com.xa.mass.task.runtime.ActiveTaskWorkSnapshot;
import com.xa.mass.task.runtime.ActiveLeaseRepairCandidate;
import com.xa.mass.task.runtime.AppendBatchOutcome;
import com.xa.mass.task.runtime.AppendItemInput;
import com.xa.mass.task.runtime.ClaimReadyOutcome;
import com.xa.mass.task.runtime.FinalResultReadRequest;
import com.xa.mass.task.runtime.FinalResultRow;
import com.xa.mass.task.runtime.FinalResultWindow;
import com.xa.mass.task.runtime.MessageFinalityOutcome;
import com.xa.mass.task.runtime.ResultCorrelationSnapshot;
import com.xa.mass.task.runtime.RuntimeEpoch;
import com.xa.mass.task.runtime.RuntimeResultFact;
import com.xa.mass.task.runtime.ScoreCandidate;
import com.xa.mass.task.runtime.ScoreCandidateBatch;
import com.xa.mass.task.runtime.TaskRuntimeMetaV1;
import com.xa.mass.task.runtime.TaskRuntimeProgressSnapshot;
import com.xa.mass.task.runtime.TaskRuntimeResultWindowReadModel;
import com.xa.mass.task.runtime.TaskScoreV1;
import com.xa.mass.task.runtime.WorkerReservationEvidence;
import com.xa.mass.task.runtime.memory.InMemoryTaskRuntime;
import java.util.List;
import java.util.Optional;

record MemoryPortSet(InMemoryTaskRuntime delegate) implements TaskRuntimePortSet, TaskRuntimeResultWindowReadModel {

    @Override
    public AppendBatchOutcome appendBacklog(String taskId, List<AppendItemInput> frames, int maxBatchSize) {
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
    public Optional<TaskScoreV1> taskScore(String taskId, String laneKey) {
        return delegate.taskScore(taskId, laneKey);
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
    public List<String> promoteDueRetries(String laneKey, long nowMillis, int taskLimit, int itemLimit) {
        return delegate.promoteDueRetries(laneKey, nowMillis, taskLimit, itemLimit);
    }

    @Override
    public List<ActiveLeaseRepairCandidate> scanExpiredLeases(String laneKey, long nowMillis, int taskLimit, int itemLimit) {
        return delegate.scanExpiredLeases(laneKey, nowMillis, taskLimit, itemLimit);
    }

    @Override
    public MessageFinalityOutcome applyResult(RuntimeResultFact fact) {
        return delegate.applyResult(fact);
    }

    @Override
    public boolean closeIfDrained(String taskId, String laneKey, RuntimeEpoch epoch) {
        return delegate.closeIfDrained(taskId, laneKey, epoch);
    }

    @Override
    public void discardRuntime(String taskId, String laneKey, RuntimeEpoch epoch, String reason) {
        delegate.discardRuntime(taskId, laneKey, epoch, reason);
    }

    @Override
    public void discardWork(String taskId, RuntimeEpoch epoch, String reason) {
        delegate.discardWork(taskId, epoch, reason);
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
    public FinalResultWindow readFinalResults(FinalResultReadRequest request) {
        return delegate.readFinalResults(request);
    }

    @Override
    public Optional<FinalResultRow> getFinalResultByMessageId(String taskId, String messageId) {
        return delegate.getFinalResultByMessageId(taskId, messageId);
    }

    @Override
    public TaskRuntimeProgressSnapshot progressSnapshot(String taskId) {
        return delegate.progressSnapshot(taskId);
    }

}
