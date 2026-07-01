package com.xa.mass.task.runtime;

import java.util.List;

public record ActiveLeaseRepairBatch(List<ActiveLeaseRepairCandidate> candidates) {

    public ActiveLeaseRepairBatch {
        candidates = TaskRuntimeContractChecks.copyList(candidates);
    }
}
