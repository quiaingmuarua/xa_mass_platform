package com.xa.mass.engine;

import com.xa.mass.base.model.Worker;
import com.xa.mass.runtime.worker.WorkerCandidateRow;

public final class TestWorkerCandidateRows {
    private TestWorkerCandidateRows() {
    }

    public static WorkerCandidateRow from(Worker worker) {
        return new WorkerCandidateRow(
                worker.getWorkerId(),
                worker.getStatus() == null ? null : worker.getStatus().name(),
                worker.getAgentVersion(),
                worker.getLastHeartbeat(),
                worker.getSupportedProjects(),
                worker.getSupportedEventCodes(),
                worker.getWorkerGroupId(),
                worker.getAdapterNodeId(),
                worker.getAdapterId(),
                worker.getOnlineStrategy(),
                worker.getMaxConcurrentWork(),
                worker.getAttributes(),
                worker.getCreateTime(),
                worker.getUpdateTime(),
                worker.isAvailable()
        );
    }
}
