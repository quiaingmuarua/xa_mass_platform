package com.xa.mass.task.runtime.memory;

import com.xa.mass.task.runtime.AppendBatchOutcome;
import com.xa.mass.task.runtime.ActiveTaskWorkSnapshot;
import com.xa.mass.task.runtime.BacklogFrameV1;
import com.xa.mass.task.runtime.ClaimReadyOutcome;
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
import com.xa.mass.task.runtime.TaskRuntimePortContractTest;
import com.xa.mass.task.runtime.TaskRuntimeMetaV1;
import com.xa.mass.task.runtime.TaskRuntimeProgressSnapshot;
import com.xa.mass.task.runtime.TaskScoreV1;
import com.xa.mass.task.runtime.WorkerReservationEvidence;
import java.util.List;
import java.util.Optional;

class InMemoryTaskRuntimeContractTest extends TaskRuntimePortContractTest {

    @Override
    protected TaskRuntimePorts createRuntime() {
        return new Ports(new InMemoryTaskRuntime(() -> 0L));
    }

    private record Ports(InMemoryTaskRuntime delegate) implements TaskRuntimePorts {

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
            return delegate.applyResult(fact);
        }

        @Override
        public TaskCloseAttemptOutcome closeIfDrained(String taskId, String laneKey, RuntimeEpoch epoch) {
            return delegate.closeIfDrained(taskId, laneKey, epoch);
        }

        @Override
        public DiscardTaskRuntimeOutcome discardRuntime(String taskId, String laneKey, RuntimeEpoch epoch, String reason) {
            return delegate.discardRuntime(taskId, laneKey, epoch, reason);
        }

        @Override
        public DiscardTaskWorkOutcome discardWork(String taskId, RuntimeEpoch epoch, String reason) {
            return delegate.discardWork(taskId, epoch, reason);
        }

        @Override
        public Optional<FinalResultRow> getFinalResultByMessageId(String taskId, String messageId) {
            return delegate.getFinalResultByMessageId(taskId, messageId);
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
    }
}
