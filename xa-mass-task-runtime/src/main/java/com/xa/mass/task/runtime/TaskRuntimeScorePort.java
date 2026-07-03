package com.xa.mass.task.runtime;

import java.util.Optional;

public interface TaskRuntimeScorePort {

    void putRuntimeMeta(TaskRuntimeMetaV1 meta);

    void setTaskScore(String taskId, String laneKey, RuntimeEpoch epoch, TaskScoreV1 score);

    void removeTaskScore(String taskId, String laneKey, RuntimeEpoch epoch);

    Optional<ScoreCandidate> scoreCandidate(String taskId, String laneKey);

    ScoreCandidateBatch discoverSchedulable(String laneKey, long maxScore, int limit);
}
