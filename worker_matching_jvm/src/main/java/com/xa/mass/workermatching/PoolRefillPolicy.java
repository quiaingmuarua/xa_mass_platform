package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.WorkerMatching.HeldCandidate;
import com.xa.mass.kernel.assignment.EligibilityQuery;
import java.util.List;
import java.util.Map;

/**
 * Supply policy for one composition-time Pool. All operations are thread-safe and Group-isolated.
 * Results are immutable snapshots; result Maps retain the supplied keys and their iteration order.
 * Held scores are opaque Kernel fences, never index coordinates. Targets use counts 1..1000;
 * Consumption is independently composed through QueryFunctions.
 */
public interface PoolRefillPolicy {
    /** Idempotent query validation/normalization, without Redis reads or stock changes. */
    EligibilityQuery normalizeQuery(String workerGroupId, EligibilityQuery query);
    /** Observed refill shortages, not reservations; at most 100 targets. Capacity may suppress refill. */
    Map<EligibilityQuery, Integer> deficits(String workerGroupId, Map<EligibilityQuery, Integer> targets);
    /**
     * Qualifies at most 100 already-held identities and returns IDs actually admitted.
     * Preserves original deadlines and fences. Validates before admitting new candidates.
     * Target counts guide an observation, not a reservation; hard capacity still bounds admission.
     * A later Pool's failure never rolls back this Pool's completed admission.
     */
    List<String> refill(String workerGroupId, Map<EligibilityQuery, Integer> targets,
            List<HeldCandidate> offered, int maxAccepted);
}
