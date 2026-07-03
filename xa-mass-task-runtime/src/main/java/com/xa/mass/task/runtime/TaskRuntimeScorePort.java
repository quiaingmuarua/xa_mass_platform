package com.xa.mass.task.runtime;

import java.util.Optional;

public interface TaskRuntimeScorePort {

    void putRuntimeMeta(TaskRuntimeMetaV1 meta);

    void seedNonSchedulable(String taskId, String laneKey, RuntimeEpoch epoch);

    void markDispatchDue(String taskId, String laneKey, RuntimeEpoch epoch, long nowMillis);

    void markSchedulerHold(String taskId, String laneKey, RuntimeEpoch epoch);

    void markTerminalRetained(String taskId, String laneKey, RuntimeEpoch epoch);

    Optional<ScoreCandidate> scoreCandidate(String taskId, String laneKey);

    ScoreCandidateBatch discoverSchedulable(String laneKey, long maxScore, int limit);
}
