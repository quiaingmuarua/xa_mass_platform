package com.xa.mass.kernel.assignment;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded Matching operations for Pacer. Kernel retains execution-lease authority. */
public interface WorkerMatching {
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

    /**
     * Local destructive consumption for at most 100 nonblank message IDs, one candidate per ID.
     * Matching validates and normalizes the entire batch before consuming stock. Equivalent
     * queries share an allocation group in first-appearance order; IDs within it retain their
     * input order. Results retain input order, omit unfulfilled IDs and never repeat a Worker.
     * IDs are invocation-local correlation only. Returned fences and deadlines are unchanged.
     * A valid empty batch does not access stock. Returns an immutable snapshot.
     */
    Map<String, HeldCandidate> take(String workerGroupId, String ruleId,
            Map<String, EligibilityQuery> queriesByMessageId);

    /** Score is an opaque exact fence; expiry is only a local inventory cleanup deadline. */
    record HeldCandidate(String workerId, long score, long expiresAtMillis) { }
}
