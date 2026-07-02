package com.xa.mass.task.runtime;

import java.util.List;

public record ScoreCandidateBatch(List<ScoreCandidate> candidates) {

    public ScoreCandidateBatch {
        candidates = TaskRuntimeContractChecks.copyList(candidates);
    }
}
