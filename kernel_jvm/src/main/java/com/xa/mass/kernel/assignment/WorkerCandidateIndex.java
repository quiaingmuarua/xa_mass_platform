package com.xa.mass.kernel.assignment;

import com.xa.mass.kernel.task.TaskItemWorkerSelector;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Matching-owned shared inventory. Kernel retains scheduling and execution lease authority.
 */
public interface WorkerCandidateIndex {
    /** One bounded read for at most 100 Task/Group coordinates. Unusable bindings map to null. */
    Map<String, @Nullable TaskQuery> prepareTaskQueries(Map<String, String> taskGroups);

    /** Local preparation once per refill Producer round, using at most 100 prepared NORMAL Tasks. */
    RefillBatch prepareRefill(Map<String, @Nullable TaskQuery> preparedTasks);

    /** Invocation-local demand and query reuse; contains no held candidates or reservations. */
    interface RefillBatch {
        /** Capacity-bounded demand observed at preparation, not a guarantee of current shortfall. */
        Set<String> groupsNeedingRefill();

        /**
         * Qualifies at most 100 read-only observations before requesting the first lease.
         * Scores remain opaque; only returned HeldCandidates may enter consumable inventory.
         */
        int refill(String workerGroupId, Map<String, Long> observedScores, CandidateLease lease);
    }

    /** Valid only during one refill call; at most one nonempty subset of the issued batch. */
    interface CandidateLease {
        List<HeldCandidate> acquire(List<String> acceptedWorkerIds);
    }

    /** Score is an opaque exact fence; expiry is only a local inventory cleanup deadline. */
    record HeldCandidate(String workerId, long score, long expiresAtMillis) { }

    /** A binding view referencing shared inventory, with no Task-private candidate state. */
    interface TaskQuery {

        /** Admission and dispatch use the same bound query semantics; performs no Redis read. */
        void validate(TaskItemWorkerSelector selector);

        /** Local destructive consumption: at most 100 queries and 100 unique candidates in total. */
        Map<TaskItemWorkerSelector, List<HeldCandidate>> take(Map<TaskItemWorkerSelector, Integer> limits);
    }
}
