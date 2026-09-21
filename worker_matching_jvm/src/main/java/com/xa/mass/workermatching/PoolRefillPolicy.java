package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import java.util.List;
import java.util.Map;

/**
 * Supply policy for one composition-time Pool. All operations are thread-safe and Group-isolated.
 * Results are immutable snapshots; result Maps retain the supplied keys and their iteration order.
 * Candidate scores are opaque Kernel fences, never index coordinates. Targets use counts 1..1000;
 * Consumption is independently composed through QueryFunction.
 */
public interface PoolRefillPolicy {
    enum TargetBatching { ALL, PAGED }
    /** How the coordinator presents this policy's targets; observation never advances a page. */
    TargetBatching targetBatching();
    /** Idempotent query validation/normalization, without Redis reads or stock changes. */
    EligibilityQuery normalizeQuery(String workerGroupId, EligibilityQuery query);
    /** Observed shortages, not reservations; ALL accepts up to 10,000 targets, PAGED at most 100. Capacity may suppress refill. */
    Map<EligibilityQuery, Integer> deficits(String workerGroupId, Map<EligibilityQuery, Integer> targets);
    /**
     * Qualifies at most 100 supplied generations and returns IDs actually admitted.
     * Preserves fences; Pool admission owns its TTL. Validates before admitting new candidates.
     * Target counts guide shortage observation, never an admission quota. Qualified offers may
     * exceed the watermark; maxAccepted and hard capacity still bound admission.
     * A later Pool's failure never rolls back this Pool's completed admission.
     */
    List<String> refill(String workerGroupId, Map<EligibilityQuery, Integer> targets,
            Map<String, Long> offered, int maxAccepted);
}
