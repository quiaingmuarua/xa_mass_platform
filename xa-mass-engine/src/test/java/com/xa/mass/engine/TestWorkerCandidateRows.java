package com.xa.mass.engine;

import com.xa.mass.base.model.Worker;
import com.xa.mass.worker.runtime.candidate.WorkerCandidateRow;

public final class TestWorkerCandidateRows {
    private TestWorkerCandidateRows() {
    }

    public static WorkerCandidateRow from(Worker worker) {
        return new WorkerCandidateRow(
                worker.getWorkerId(),
                worker.getAgentVersion(),
                worker.getWorkerGroupId() == null || worker.getWorkerGroupId().isBlank()
                        ? "group-a"
                        : worker.getWorkerGroupId(),
                worker.getOnlineStrategy(),
                worker.getAttributes()
        );
    }
}
