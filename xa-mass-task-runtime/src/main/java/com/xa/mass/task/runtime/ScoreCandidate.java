package com.xa.mass.task.runtime;

public record ScoreCandidate(
        String taskId,
        String laneKey,
        RuntimeEpoch runtimeEpoch,
        TaskScoreV1 observedScore
) {

    public ScoreCandidate {
        taskId = TaskRuntimeContractChecks.requireText(taskId, "taskId");
        laneKey = TaskRuntimeContractChecks.requireText(laneKey, "laneKey");
        runtimeEpoch = runtimeEpoch == null ? RuntimeEpoch.of(taskId, 0L) : runtimeEpoch;
        observedScore = observedScore == null ? TaskScoreV1.dueAt(0L) : observedScore;
    }

    public static ScoreCandidate fromSchedulerCandidate(String laneKey, SchedulerTaskCandidate candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate is required");
        }
        return new ScoreCandidate(
                candidate.taskId(),
                laneKey,
                candidate.runtimeEpoch(),
                TaskScoreV1.dueAt(candidate.eligibleAtMillis()));
    }
}
