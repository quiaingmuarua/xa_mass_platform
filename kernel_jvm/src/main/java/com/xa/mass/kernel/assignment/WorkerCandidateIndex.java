package com.xa.mass.kernel.assignment;

import com.xa.mass.kernel.task.TaskItemWorkerSelector;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Matching-owned shared inventory. Kernel retains scheduling and execution lease authority.
 */
public interface WorkerCandidateIndex {
    /** One bounded read for at most 100 Task/Group coordinates. Unusable bindings map to null. */
    Map<String, @Nullable TaskQuery> prepareTaskQueries(Map<String, String> taskGroups);

    /** Replenishes shared inventory from prepared NORMAL Tasks, without observing their Items. */
    int refill(Map<String, @Nullable TaskQuery> tasks, int budget, InitialHold kernelHold);

    /** Fixed refill collaboration. Each call requests at most 100 initial holds. */
    interface InitialHold {
        List<HeldCandidate> any(String workerGroupId, int limit);
        List<HeldCandidate> identities(String workerGroupId, List<String> workerIds, int limit);
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
