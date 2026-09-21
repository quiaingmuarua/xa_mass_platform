package com.xa.mass.kernel.assignment;

import java.util.List;
import java.util.Map;

/** Bounded Matching operations for Pacer. Kernel retains execution-lease authority. */
public interface WorkerMatching {
    /**
     * Positive Group shortage hints in input Group order, as an immutable snapshot.
     * Missing Groups have no observed shortage. Counts are not reservations or distinct
     * Worker counts: overlapping targets and Pools may share a candidate. At most 100
     * Groups and 10,000 target declarations; retains existing capacity limits.
     * Performs global lazy expiry and inactive refill-cursor cleanup, including on empty input.
     */
    Map<String, Integer> observeRefillDeficits(Map<String, List<RefillTarget>> refillByGroup);

    /**
     * Qualifies at most 100 unique candidate generations supplied by Pacer for this Group.
     * Does not require an earlier shortage observation. Preserves opaque fences and uses local inventory TTL;
     * Each supplied generation is offered only until one Pool admits it. Later Rule failure
     * does not undo earlier admissions. Target bounds match the observation. Pacer supplies
     * each successful candidateization once; this operation does not replay retained stock.
     */
    int refill(String workerGroupId, List<RefillTarget> declarations,
            Map<String, Long> candidateScores);

    /**
     * Bounded candidate lookup for at most 100 nonblank message IDs, one candidate per ID.
     * Matching validates and normalizes the entire batch before executing fixed named functions.
     * Functions execute in first-appearance order and own their local input semantics. Current
     * Pool functions consume local stock; direct functions locate identities without stock or
     * lease operations. Pool selections retain Item order within each equivalent group.
     * Results retain input order, omit unfulfilled IDs and never repeat a Worker.
     * IDs are invocation-local correlation only. A nonzero expected score is an exact fence;
     * zero is an identity hint with no historical fence. Neither grants execution authority.
     * A valid empty batch does not access stock. Returns an immutable snapshot.
     */
    Map<String, WorkerCandidate> take(String workerGroupId,
            Map<String, WorkerQuery> queriesByMessageId);

    /** Pacer selects current-state execution acquisition for zero, exact due acquisition otherwise; scores stay opaque. */
    record WorkerCandidate(String workerId, long expectedScore) {
        public WorkerCandidate {
            if (workerId == null || workerId.isBlank()) {
                throw new IllegalArgumentException("workerId must be non-blank");
            }
        }
    }

}
