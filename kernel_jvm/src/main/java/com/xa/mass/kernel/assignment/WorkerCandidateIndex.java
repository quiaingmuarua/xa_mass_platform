package com.xa.mass.kernel.assignment;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Matching-owned shared inventory. Pacer carries names and data; Kernel owns execution leases. */
public interface WorkerCandidateIndex {
    /** Idempotent Rule admission, without Redis reads or stock changes. */
    EligibilityQuery normalizeQuery(String workerGroupId, String ruleId, EligibilityQuery query);

    /**
     * Capacity-bounded hints, not reservations. At most 100 Group/Rule coordinates and 10,000
     * target declarations. Performs global lazy expiry and inactive refill-cursor cleanup.
     */
    Set<String> groupsNeedingRefill(Map<String, Map<String, List<RefillTarget>>> targetsByGroup);

    /**
     * Qualifies at most 100 unique candidates already leased by Pacer for this Group.
     * Does not require an earlier shortage observation. Preserves opaque fences and deadlines;
     * later Rule failure does not undo earlier admissions. Target bounds match the observation.
     */
    int refill(String workerGroupId, Map<String, List<RefillTarget>> targetsByRule,
            List<HeldCandidate> offeredCandidates);

    /** Local destructive consumption: at most 100 queries and 100 unique candidates in total. */
    Map<EligibilityQuery, List<HeldCandidate>> take(String workerGroupId, String ruleId,
            Map<EligibilityQuery, Integer> limits);

    /** Score is an opaque exact fence; expiry is only a local inventory cleanup deadline. */
    record HeldCandidate(String workerId, long score, long expiresAtMillis) { }
}
