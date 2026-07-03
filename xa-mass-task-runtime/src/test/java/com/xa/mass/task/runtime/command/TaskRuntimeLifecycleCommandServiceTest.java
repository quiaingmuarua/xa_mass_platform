package com.xa.mass.task.runtime.command;

import com.xa.mass.task.runtime.RuntimeEpoch;
import com.xa.mass.task.runtime.ActiveLeaseRepairCandidate;
import com.xa.mass.task.runtime.AppendBatchOutcome;
import com.xa.mass.task.runtime.AppendBatchStatus;
import com.xa.mass.task.runtime.AppendItemInput;
import com.xa.mass.task.runtime.ClaimReadyOutcome;
import com.xa.mass.task.runtime.MessageFinalityOutcome;
import com.xa.mass.task.runtime.RuntimeResultFact;
import com.xa.mass.task.runtime.ScoreCandidate;
import com.xa.mass.task.runtime.ScoreCandidateBatch;
import com.xa.mass.task.runtime.TaskRuntimeConvergencePort;
import com.xa.mass.task.runtime.TaskRuntimeMetaV1;
import com.xa.mass.task.runtime.TaskRuntimeScorePort;
import com.xa.mass.task.runtime.TaskRuntimeWorkPort;
import com.xa.mass.task.runtime.TaskScoreV1;
import com.xa.mass.task.runtime.WorkerReservationEvidence;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TaskRuntimeLifecycleCommandServiceTest {

    @Test
    void createWritesPositiveNonSchedulableScore() {
        var scores = new FakeScorePort();
        var commands = new TaskRuntimeLifecycleCommandService(scores, "default", () -> 1_000L, 86_400_000L);

        var outcome = commands.create("task-1");

        assertThat(outcome.accepted()).isTrue();
        assertThat(scores.taskScore("task-1", "default"))
                .hasValueSatisfying(score -> {
                    assertThat(score.isPositiveNonSchedulableBand()).isTrue();
                    assertThat(score.score()).isEqualTo(TaskScoreV1.NON_SCHED_CREATED);
                });
    }

    @Test
    void approveWritesDueTimestampScoreWithoutEngineStatus() {
        var scores = new FakeScorePort();
        var commands = new TaskRuntimeLifecycleCommandService(scores, "default", () -> 1_000L, 86_400_000L);

        var outcome = commands.approve("task-1");

        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.applied()).isTrue();
        assertThat(scores.taskScore("task-1", "default"))
                .hasValueSatisfying(score -> {
                    assertThat(score.isSchedulableBand()).isTrue();
                    assertThat(score.score()).isEqualTo(TaskScoreV1.TIME_SCORE_FLOOR);
                });
    }

    @Test
    void pauseIsEventAndUsesRuntimeDefaultFutureScore() {
        var scores = new FakeScorePort();
        var commands = new TaskRuntimeLifecycleCommandService(scores, "default", () -> TaskScoreV1.TIME_SCORE_FLOOR, 86_400_000L);
        commands.approve("task-1");

        var outcome = commands.pause("task-1");

        assertThat(outcome.accepted()).isTrue();
        assertThat(scores.taskScore("task-1", "default"))
                .hasValueSatisfying(score -> assertThat(score.score())
                        .isEqualTo(TaskScoreV1.TIME_SCORE_FLOOR + 86_400_000L));
    }

    @Test
    void blockWritesPositiveNonSchedulableScoreAndRejectWritesTerminalScore() {
        var scores = new FakeScorePort();
        var commands = new TaskRuntimeLifecycleCommandService(scores, "default", () -> 0L, 86_400_000L);

        assertThat(commands.block("blocked-task").accepted()).isTrue();
        assertThat(scores.taskScore("blocked-task", "default"))
                .hasValueSatisfying(score -> {
                    assertThat(score.isPositiveNonSchedulableBand()).isTrue();
                    assertThat(score.isSchedulableBand()).isFalse();
                });

        assertThat(commands.reject("rejected-task").accepted()).isTrue();
        assertThat(scores.taskScore("rejected-task", "default"))
                .hasValueSatisfying(score -> assertThat(score.isTerminalBand()).isTrue());
    }

    @Test
    void terminalScoreRejectsLaterApprove() {
        var scores = new FakeScorePort();
        var commands = new TaskRuntimeLifecycleCommandService(scores, "default", () -> 0L, 86_400_000L);
        commands.reject("task-1");

        var outcome = commands.approve("task-1");

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.reasonCode()).isEqualTo("TASK_TERMINAL");
    }

    @Test
    void resumeMovesFutureSchedulableScoreBackToDueTimestamp() {
        var scores = new FakeScorePort();
        var commands = new TaskRuntimeLifecycleCommandService(scores, "default", () -> TaskScoreV1.TIME_SCORE_FLOOR, 86_400_000L);
        commands.approve("task-1");
        commands.pause("task-1");

        var outcome = commands.resume("task-1");

        assertThat(outcome.accepted()).isTrue();
        assertThat(scores.taskScore("task-1", "default"))
                .hasValueSatisfying(score -> assertThat(score.score()).isEqualTo(TaskScoreV1.TIME_SCORE_FLOOR));
    }

    @Test
    void resumeRejectsTerminalScore() {
        var scores = new FakeScorePort();
        var commands = new TaskRuntimeLifecycleCommandService(scores, "default", () -> 0L, 86_400_000L);
        commands.reject("task-1");

        var outcome = commands.resume("task-1");

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.reasonCode()).isEqualTo("TASK_TERMINAL");
    }

    @Test
    void appendWritesBacklogWithoutChangingTaskScore() {
        var runtime = new FakeRuntimePort();
        var commands = new TaskRuntimeLifecycleCommandService(runtime, runtime, "default", () -> 0L, 86_400_000L);

        var outcome = commands.append(
                "task-1",
                java.util.List.of(new AppendItemInput("message-1", Map.of("value", 1))),
                10);

        assertThat(outcome.status()).isEqualTo(AppendBatchStatus.ALL_ACCEPTED);
        assertThat(runtime.taskScore("task-1", "default")).isEmpty();
        assertThat(runtime.appendedMessageIds).containsExactly("message-1");
    }

    @Test
    void cancelDiscardsRuntimeWorkAndWritesTerminalScore() {
        var runtime = new FakeRuntimePort();
        var commands = new TaskRuntimeLifecycleCommandService(runtime, runtime, runtime, "default", () -> 0L, 86_400_000L);
        commands.approve("task-1");

        var outcome = commands.cancel("task-1");

        assertThat(outcome.accepted()).isTrue();
        assertThat(runtime.discardedWork).containsEntry("task-1", "MANUAL_CANCELLED");
        assertThat(runtime.taskScore("task-1", "default"))
                .hasValueSatisfying(score -> assertThat(score.isTerminalBand()).isTrue());
    }

    @Test
    void terminateRejectsAlreadyTerminalTask() {
        var runtime = new FakeRuntimePort();
        var commands = new TaskRuntimeLifecycleCommandService(runtime, runtime, runtime, "default", () -> 0L, 86_400_000L);
        commands.cancel("task-1");

        var outcome = commands.terminate("task-1", "MAX_RUNTIME_REACHED");

        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.reasonCode()).isEqualTo("TASK_ALREADY_TERMINAL");
        assertThat(runtime.discardedWork).containsEntry("task-1", "MANUAL_CANCELLED");
    }

    private static class FakeScorePort implements TaskRuntimeScorePort {
        private final Map<String, TaskScoreV1> scores = new LinkedHashMap<>();

        @Override
        public void putRuntimeMeta(TaskRuntimeMetaV1 meta) {
        }

        @Override
        public void setTaskScore(String taskId, String laneKey, RuntimeEpoch epoch, TaskScoreV1 score) {
            scores.put(taskId + "@" + laneKey, score);
        }

        @Override
        public void removeTaskScore(String taskId, String laneKey, RuntimeEpoch epoch) {
            scores.remove(taskId + "@" + laneKey);
        }

        @Override
        public Optional<TaskScoreV1> taskScore(String taskId, String laneKey) {
            return Optional.ofNullable(scores.get(taskId + "@" + laneKey));
        }

        @Override
        public Optional<ScoreCandidate> scoreCandidate(String taskId, String laneKey) {
            return Optional.empty();
        }

        @Override
        public ScoreCandidateBatch discoverSchedulable(String laneKey, long maxScore, int limit) {
            return new ScoreCandidateBatch(java.util.List.of());
        }
    }

    private static final class FakeRuntimePort extends FakeScorePort
            implements TaskRuntimeWorkPort, TaskRuntimeConvergencePort {
        private final java.util.List<String> appendedMessageIds = new java.util.ArrayList<>();
        private final Map<String, String> discardedWork = new LinkedHashMap<>();

        @Override
        public AppendBatchOutcome appendBacklog(String taskId, java.util.List<AppendItemInput> frames, int maxBatchSize) {
            appendedMessageIds.addAll(frames.stream().map(AppendItemInput::messageId).toList());
            return AppendBatchOutcome.allAccepted(taskId, appendedMessageIds);
        }

        @Override
        public ClaimReadyOutcome claimBacklog(ScoreCandidate candidate,
                                              java.util.List<WorkerReservationEvidence> reservations,
                                              int maxItems,
                                              long leaseMillis,
                                              long nowMillis) {
            return new ClaimReadyOutcome(candidate.taskId(), java.util.List.of(), "not used");
        }

        @Override
        public java.util.List<String> promoteDueRetries(String laneKey, long nowMillis, int taskLimit, int itemLimit) {
            return java.util.List.of();
        }

        @Override
        public java.util.List<ActiveLeaseRepairCandidate> scanExpiredLeases(String laneKey,
                                                                            long nowMillis,
                                                                            int taskLimit,
                                                                            int itemLimit) {
            return java.util.List.of();
        }

        @Override
        public MessageFinalityOutcome applyResult(RuntimeResultFact fact) {
            return MessageFinalityOutcome.duplicateOrLate(
                    fact.taskId(), fact.messageId(), fact.attemptNo(), "not used");
        }

        @Override
        public boolean closeIfDrained(String taskId, String laneKey, RuntimeEpoch epoch) {
            return false;
        }

        @Override
        public void discardRuntime(String taskId, String laneKey, RuntimeEpoch epoch, String reason) {
            discardedWork.put(taskId, reason);
        }

        @Override
        public void discardWork(String taskId, RuntimeEpoch epoch, String reason) {
            discardedWork.put(taskId, reason);
        }
    }
}
