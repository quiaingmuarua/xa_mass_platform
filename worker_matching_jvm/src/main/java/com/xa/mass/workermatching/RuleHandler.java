package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.WorkerCandidateIndex.HeldCandidate;
import com.xa.mass.kernel.task.TaskItemWorkerSelector;
import java.util.List;
import java.util.Map;

/**
 * Eligibility owner for one composition-time Rule. All operations are thread-safe and Group-isolated.
 * Results are immutable snapshots. Held scores are opaque Kernel fences, never index coordinates.
 */
public interface RuleHandler {
    /** Validates and normalizes a refill target without changing stock. */
    EligibilityQuery normalizeTarget(String workerGroupId, EligibilityQuery target);
    /** Validates an Item selector without Redis reads or stock changes. */
    void validateSelector(String workerGroupId, TaskItemWorkerSelector selector);
    /** Observed refill shortages, not reservations; at most 100 targets. Capacity may suppress refill. */
    Map<EligibilityQuery, Integer> deficits(String workerGroupId, List<EligibilityQuery> targets);
    /**
     * Qualifies at most 100 already-held identities and returns IDs actually admitted.
     * Preserves original deadlines and fences. Validates before admitting new candidates.
     * A later Rule's failure never rolls back this Rule's completed admission.
     */
    List<String> refill(String workerGroupId, List<EligibilityQuery> targets,
            List<HeldCandidate> offered, int maxAccepted);
    /** Validates and atomically consumes at most 100 candidates across at most 100 selectors. */
    Map<TaskItemWorkerSelector, List<HeldCandidate>> take(String workerGroupId,
            Map<TaskItemWorkerSelector, Integer> limits);
}
