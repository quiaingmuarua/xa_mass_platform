package com.xa.mass.task.runtime;

import java.util.List;

public record SchedulerDiscoveryOutcome(List<SchedulerTaskCandidate> candidates) {

    public SchedulerDiscoveryOutcome {
        candidates = TaskRuntimeContractChecks.copyList(candidates);
    }
}
