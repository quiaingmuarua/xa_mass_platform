package com.xa.mass.kernel.assignment;

import java.util.List;

/** Immutable Task configuration data. Matching owns its persistence and Rule interpretation. */
public record TaskRuleBinding(
        String ruleId,
        String workerGroupId,
        List<RefillTarget> refillTargets) {
    public TaskRuleBinding {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId must be non-blank");
        }
        if (workerGroupId == null || workerGroupId.isBlank()) {
            throw new IllegalArgumentException("workerGroupId must be non-blank");
        }
        refillTargets = List.copyOf(refillTargets);
        if (refillTargets.isEmpty() || refillTargets.size() > 100) {
            throw new IllegalArgumentException("refillTargets must contain 1..100 queries");
        }
    }
}
