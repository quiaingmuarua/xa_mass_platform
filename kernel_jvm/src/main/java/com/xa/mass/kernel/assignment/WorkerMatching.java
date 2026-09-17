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
     * Matching validates and normalizes the entire batch before executing fixed named functions.
     * Functions execute in first-appearance order and own their local input semantics. Current
     * Pool functions group equivalent selections and retain Item order within each group. Results retain input order, omit unfulfilled IDs and never repeat a Worker.
     * IDs are invocation-local correlation only. A nonzero expected score is an exact fence;
     * zero is an identity hint with no historical fence. Neither grants execution authority.
     * A valid empty batch does not access stock. Returns an immutable snapshot.
     */
    Map<String, WorkerCandidate> take(String workerGroupId,
            Map<String, WorkerQuery> queriesByMessageId);

    /** Pacer selects current-state transfer for zero, exact transfer otherwise; the score stays opaque. */
    record WorkerCandidate(String workerId, long expectedScore) {
        public WorkerCandidate {
            if (workerId == null || workerId.isBlank()) {
                throw new IllegalArgumentException("workerId must be non-blank");
            }
        }
    }

    /** Score is an opaque exact fence; expiry is only a local inventory cleanup deadline. */
    record HeldCandidate(String workerId, long score, long expiresAtMillis) { }
}
