package com.xa.mass.task.runtime;

import java.util.List;

public record LeaseRepairBatch(List<ActiveLeaseRepairCandidate> candidates) {

    public LeaseRepairBatch {
        candidates = TaskRuntimeContractChecks.copyList(candidates);
    }

    public static LeaseRepairBatch from(ActiveLeaseRepairBatch batch) {
        return new LeaseRepairBatch(batch == null ? List.of() : batch.candidates());
    }
}
