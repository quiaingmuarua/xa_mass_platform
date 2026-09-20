package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.workermatching.pool.CandidatePool.RetainedCandidate;
import java.util.List;
import java.util.Map;

/**
 * Supply policy for one composition-time Pool. All operations are thread-safe and Group-isolated.
 * Results are immutable snapshots; result Maps retain the supplied keys and their iteration order.
 * Candidate scores are opaque Kernel fences, never index coordinates. Targets use counts 1..1000;
 * Consumption is independently composed through QueryFunction.
 */
public interface PoolRefillPolicy {
    /** Idempotent query validation/normalization, without Redis reads or stock changes. */
    EligibilityQuery normalizeQuery(String workerGroupId, EligibilityQuery query);
    /** Observed refill shortages, not reservations; Country accepts up to 10,000 targets together; other policies at most 100. Capacity may suppress refill. */
    Map<EligibilityQuery, Integer> deficits(String workerGroupId, Map<EligibilityQuery, Integer> targets);
    /**
     * Qualifies at most 100 supplied generations and returns IDs actually admitted.
     * Preserves fences; Pool admission owns its TTL. Validates before admitting new candidates.
     * Target counts guide an observation, not a reservation; hard capacity still bounds admission.
     * A later Pool's failure never rolls back this Pool's completed admission.
     */
    List<String> refill(String workerGroupId, Map<EligibilityQuery, Integer> targets,
            Map<String, Long> offered, int maxAccepted);

    /** Requalifies retained generations without replacing stock or extending their original TTL. */
    List<String> refillRetained(String workerGroupId, Map<EligibilityQuery, Integer> targets,
            Map<String, RetainedCandidate> offered, int maxAccepted);
}
