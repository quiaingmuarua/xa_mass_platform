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

}
