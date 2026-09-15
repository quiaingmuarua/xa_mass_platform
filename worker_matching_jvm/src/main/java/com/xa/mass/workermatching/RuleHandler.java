package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.WorkerCandidateIndex.HeldCandidate;
import com.xa.mass.kernel.assignment.EligibilityQuery;
import java.util.List;
import java.util.Map;

/**
 * Eligibility owner for one composition-time Rule. All operations are thread-safe and Group-isolated.
 * Results are immutable snapshots; result Maps retain the supplied keys and their iteration order.
 * Held scores are opaque Kernel fences, never index coordinates. Targets use counts 1..1000;
 * take permits 1..100 per query and at most 100 candidates in total.
 */
public interface RuleHandler {
    /** Idempotent query validation/normalization, without Redis reads or stock changes. */
    EligibilityQuery normalizeQuery(String workerGroupId, EligibilityQuery query);
    /** Observed refill shortages, not reservations; at most 100 targets. Capacity may suppress refill. */
    Map<EligibilityQuery, Integer> deficits(String workerGroupId, Map<EligibilityQuery, Integer> targets);
    /**
     * Qualifies at most 100 already-held identities and returns IDs actually admitted.
     * Preserves original deadlines and fences. Validates before admitting new candidates.
     * Target counts guide an observation, not a reservation; hard capacity still bounds admission.
     * A later Rule's failure never rolls back this Rule's completed admission.
     */
    List<String> refill(String workerGroupId, Map<EligibilityQuery, Integer> targets,
            List<HeldCandidate> offered, int maxAccepted);
    /**
     * Validates and consumes at most 100 candidates across at most 100 queries.
     * Each consumed entry must still be current and live at commit. Concurrent changes can leave
     * a short result; they do not require retrying selection within this call.
     */
    Map<EligibilityQuery, List<HeldCandidate>> take(String workerGroupId,
            Map<EligibilityQuery, Integer> limits);
}
