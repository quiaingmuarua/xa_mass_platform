package com.xa.mass.task.runtime.command;

import com.xa.mass.task.runtime.RuntimeEpoch;
import com.xa.mass.task.runtime.RuntimeGate;
import com.xa.mass.task.runtime.AppendBatchOutcome;
import com.xa.mass.task.runtime.AppendItemInput;
import com.xa.mass.task.runtime.ClaimReadyOutcome;
import com.xa.mass.task.runtime.TaskRuntimeMetaV1;
import com.xa.mass.task.runtime.TaskRuntimeConvergencePort;
import com.xa.mass.task.runtime.TaskRuntimeScorePort;
import com.xa.mass.task.runtime.TaskRuntimeWorkPort;
import com.xa.mass.task.runtime.TaskScoreV1;
import com.xa.mass.task.runtime.ScoreCandidate;
import com.xa.mass.task.runtime.WorkerReservationEvidence;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

public final class TaskRuntimeLifecycleCommandService implements TaskRuntimeCommandPort {

    public static final String DEFAULT_LANE_KEY = "default";
    public static final long DEFAULT_PAUSE_DELAY_MILLIS = 86_400_000L;

    private final TaskRuntimeScorePort scores;
    private final TaskRuntimeWorkPort work;
    private final TaskRuntimeConvergencePort convergence;
    private final String laneKey;
    private final LongSupplier clock;
    private final long pauseDelayMillis;

    public TaskRuntimeLifecycleCommandService(TaskRuntimeScorePort scores) {
        this(scores, workPort(scores), DEFAULT_LANE_KEY, System::currentTimeMillis, DEFAULT_PAUSE_DELAY_MILLIS);
    }

    public TaskRuntimeLifecycleCommandService(TaskRuntimeScorePort scores,
                                              String laneKey,
                                              LongSupplier clock,
                                              long pauseDelayMillis) {
        this(scores, workPort(scores), laneKey, clock, pauseDelayMillis);
    }

    public TaskRuntimeLifecycleCommandService(TaskRuntimeScorePort scores,
                                              TaskRuntimeWorkPort work,
                                              String laneKey,
                                              LongSupplier clock,
                                              long pauseDelayMillis) {
        this(scores, work, convergencePort(scores), laneKey, clock, pauseDelayMillis);
    }

    public TaskRuntimeLifecycleCommandService(TaskRuntimeScorePort scores,
                                              TaskRuntimeWorkPort work,
                                              TaskRuntimeConvergencePort convergence,
                                              String laneKey,
                                              LongSupplier clock,
                                              long pauseDelayMillis) {
        this.scores = Objects.requireNonNull(scores, "scores");
        this.work = Objects.requireNonNull(work, "work");
        this.convergence = Objects.requireNonNull(convergence, "convergence");
        this.laneKey = requireText(laneKey, "laneKey");
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.pauseDelayMillis = Math.max(1L, pauseDelayMillis);
    }

    @Override
    public TaskRuntimeCommandOutcome create(String taskId) {
        String id = taskId(taskId);
        Optional<TaskScoreV1> current = scores.taskScore(id, laneKey);
        if (current.isPresent()) {
            return TaskRuntimeCommandOutcome.conflict(id, "TASK_ALREADY_EXISTS", "Task runtime state already exists");
        }
        writeScore(id, TaskScoreV1.createdPending(), RuntimeGate.BLOCKED);
        return TaskRuntimeCommandOutcome.applied(id, "TASK_CREATED", "Task runtime created");
    }

    @Override
    public TaskRuntimeCommandOutcome approve(String taskId) {
        String id = taskId(taskId);
        Optional<TaskScoreV1> current = scores.taskScore(id, laneKey);
        if (current.isPresent() && current.get().isTerminalBand()) {
            return TaskRuntimeCommandOutcome.conflict(id, "TASK_TERMINAL", "Task is terminal");
        }
        if (current.isPresent() && current.get().isSchedulableBand()) {
            return TaskRuntimeCommandOutcome.alreadyApplied(id, "TASK_ALREADY_SCHEDULABLE", "Task is already schedulable");
        }
        writeScore(id, TaskScoreV1.dueAt(clock.getAsLong()), RuntimeGate.OPEN);
        return TaskRuntimeCommandOutcome.applied(id, "TASK_APPROVED", "Task approved");
    }

    @Override
    public TaskRuntimeCommandOutcome reject(String taskId) {
        String id = taskId(taskId);
        Optional<TaskScoreV1> current = scores.taskScore(id, laneKey);
        if (current.isPresent() && current.get().isTerminalBand()) {
            return TaskRuntimeCommandOutcome.alreadyApplied(id, "TASK_ALREADY_TERMINAL", "Task is already terminal");
        }
        if (current.isPresent() && current.get().isSchedulableBand()) {
            return TaskRuntimeCommandOutcome.conflict(id, "TASK_ALREADY_SCHEDULABLE", "Schedulable task cannot be rejected");
        }
        writeScore(id, TaskScoreV1.rejectedTerminal(), RuntimeGate.TERMINAL);
        return TaskRuntimeCommandOutcome.applied(id, "TASK_REJECTED", "Task rejected");
    }

    @Override
    public TaskRuntimeCommandOutcome block(String taskId) {
        String id = taskId(taskId);
        Optional<TaskScoreV1> current = scores.taskScore(id, laneKey);
        if (current.isPresent() && current.get().isTerminalBand()) {
            return TaskRuntimeCommandOutcome.conflict(id, "TASK_TERMINAL", "Task is terminal");
        }
        if (current.isPresent() && current.get().isSchedulableBand()) {
            return TaskRuntimeCommandOutcome.conflict(id, "TASK_ALREADY_SCHEDULABLE", "Schedulable task cannot move to manual hold");
        }
        if (current.isPresent() && current.get().score() == TaskScoreV1.NON_SCHED_MANUAL_BLOCKED) {
            return TaskRuntimeCommandOutcome.alreadyApplied(id, "TASK_ALREADY_BLOCKED", "Task is already blocked");
        }
        writeScore(id, TaskScoreV1.manualBlocked(), RuntimeGate.BLOCKED);
        return TaskRuntimeCommandOutcome.applied(id, "TASK_BLOCKED", "Task blocked");
    }

    @Override
    public TaskRuntimeCommandOutcome pause(String taskId) {
        String id = taskId(taskId);
        Optional<TaskScoreV1> current = scores.taskScore(id, laneKey);
        if (current.isEmpty() || current.get().isPositiveNonSchedulableBand()) {
            return TaskRuntimeCommandOutcome.conflict(id, "TASK_NOT_SCHEDULABLE", "Only schedulable tasks can be paused");
        }
        if (current.get().isTerminalBand()) {
            return TaskRuntimeCommandOutcome.conflict(id, "TASK_TERMINAL", "Task is terminal");
        }
        long defaultResumeAtMillis = clock.getAsLong() + pauseDelayMillis;
        writeScore(id, TaskScoreV1.dueAt(defaultResumeAtMillis), RuntimeGate.OPEN);
        return TaskRuntimeCommandOutcome.applied(id, "TASK_PAUSED", "Task paused");
    }

    @Override
    public TaskRuntimeCommandOutcome resume(String taskId) {
        String id = taskId(taskId);
        Optional<TaskScoreV1> current = scores.taskScore(id, laneKey);
        if (current.isPresent() && current.get().isTerminalBand()) {
            return TaskRuntimeCommandOutcome.conflict(id, "TASK_TERMINAL", "Task is terminal");
        }
        long now = clock.getAsLong();
        if (current.isPresent()
                && current.get().isSchedulableBand()
                && current.get().score() <= TaskScoreV1.dueAt(now).score()) {
            return TaskRuntimeCommandOutcome.alreadyApplied(id, "TASK_ALREADY_RESUMED", "Task is already schedulable");
        }
        writeScore(id, TaskScoreV1.dueAt(now), RuntimeGate.OPEN);
        return TaskRuntimeCommandOutcome.applied(id, "TASK_RESUMED", "Task resumed");
    }

    @Override
    public AppendBatchOutcome append(String taskId, List<AppendItemInput> items, int maxBatchSize) {
        String id = taskId(taskId);
        if (items == null || items.isEmpty()) {
            return AppendBatchOutcome.rejectedBeforeRuntime(id, "append items must be non-empty");
        }
        return work.appendBacklog(id, items, maxBatchSize);
    }

    @Override
    public TaskRuntimeCommandOutcome cancel(String taskId) {
        return terminateInternal(taskId, "MANUAL_CANCELLED", "TASK_CANCELLED", "Task cancelled");
    }

    @Override
    public TaskRuntimeCommandOutcome terminate(String taskId, String reasonCode) {
        String normalizedReason = normalizeReason(reasonCode);
        return terminateInternal(taskId, normalizedReason, "TASK_TERMINATED", "Task terminated: " + normalizedReason);
    }

    private TaskRuntimeCommandOutcome terminateInternal(String taskId,
                                                        String terminalReason,
                                                        String outcomeCode,
                                                        String message) {
        String id = taskId(taskId);
        Optional<TaskScoreV1> current = scores.taskScore(id, laneKey);
        if (current.isPresent() && current.get().isTerminalBand()) {
            return TaskRuntimeCommandOutcome.alreadyApplied(id, "TASK_ALREADY_TERMINAL", "Task is already terminal");
        }
        RuntimeEpoch epoch = RuntimeEpoch.of(id, 0L);
        convergence.discardWork(id, epoch, terminalReason);
        writeScore(id, TaskScoreV1.cancelledTerminal(), RuntimeGate.TERMINAL);
        return TaskRuntimeCommandOutcome.applied(id, outcomeCode, message);
    }

    private void writeScore(String taskId, TaskScoreV1 score, RuntimeGate gate) {
        RuntimeEpoch epoch = RuntimeEpoch.of(taskId, 0L);
        scores.putRuntimeMeta(new TaskRuntimeMetaV1(taskId, laneKey, gate, epoch, score.score(), 0L, 0L, 0L));
        scores.setTaskScore(taskId, laneKey, epoch, score);
    }

    private static String taskId(String taskId) {
        return requireText(taskId, "taskId");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static TaskRuntimeWorkPort workPort(TaskRuntimeScorePort scores) {
        if (scores instanceof TaskRuntimeWorkPort workPort) {
            return workPort;
        }
        return new UnavailableWorkPort();
    }

    private static TaskRuntimeConvergencePort convergencePort(TaskRuntimeScorePort scores) {
        if (scores instanceof TaskRuntimeConvergencePort convergencePort) {
            return convergencePort;
        }
        return new UnavailableConvergencePort();
    }

    private static String normalizeReason(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return "MANUAL_CANCELLED";
        }
        return reasonCode.trim();
    }

    private static final class UnavailableWorkPort implements TaskRuntimeWorkPort {

        @Override
        public AppendBatchOutcome appendBacklog(String taskId, List<AppendItemInput> frames, int maxBatchSize) {
            return AppendBatchOutcome.rejectedBeforeRuntime(taskId, "task runtime work port is unavailable");
        }

        @Override
        public ClaimReadyOutcome claimBacklog(ScoreCandidate candidate,
                                              List<WorkerReservationEvidence> reservations,
                                              int maxItems,
                                              long leaseMillis,
                                              long nowMillis) {
            String taskId = candidate != null ? candidate.taskId() : "unknown-task";
            return new ClaimReadyOutcome(taskId, List.of(), "task runtime work port is unavailable");
        }
    }

    private static final class UnavailableConvergencePort implements TaskRuntimeConvergencePort {

        @Override
        public List<String> promoteDueRetries(String laneKey, long nowMillis, int taskLimit, int itemLimit) {
            return List.of();
        }

        @Override
        public List<com.xa.mass.task.runtime.ActiveLeaseRepairCandidate> scanExpiredLeases(String laneKey,
                                                                                           long nowMillis,
                                                                                           int taskLimit,
                                                                                           int itemLimit) {
            return List.of();
        }

        @Override
        public com.xa.mass.task.runtime.MessageFinalityOutcome applyResult(
                com.xa.mass.task.runtime.RuntimeResultFact fact) {
            return com.xa.mass.task.runtime.MessageFinalityOutcome.duplicateOrLate(
                    fact.taskId(), fact.messageId(), fact.attemptNo(), "task runtime convergence port is unavailable");
        }

        @Override
        public boolean closeIfDrained(String taskId, String laneKey, RuntimeEpoch epoch) {
            return false;
        }

        @Override
        public void discardRuntime(String taskId, String laneKey, RuntimeEpoch epoch, String reason) {
        }

        @Override
        public void discardWork(String taskId, RuntimeEpoch epoch, String reason) {
        }
    }
}
