package com.xa.mass.kernel.assignment;

import com.xa.mass.kernel.task.TaskItemWorkerSelector;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Matching-owned property selector interpretation and bounded identity evidence.
 * Unbound callers handle ANY/explicit IDs themselves and pass property selectors unchanged.
 * A Task query constrains every selector, including ANY and explicit IDs, to its binding.
 * Index evidence does not establish HOT availability, a lease or a Candidate Cache.
 */
public interface WorkerCandidateIndex {
    /** Resolves one immutable Task binding. Missing or unusable bindings return null, never ANY. */
    @Nullable TaskQuery prepareTaskQuery(String taskId, String workerGroupId);

    /** One dispatch-local query; owns no cache, thread or close lifecycle. */
    interface TaskQuery {
        /** At most 100 queries, requesting at most 100 identities in total. */
        Map<TaskItemWorkerSelector, List<String>> take(Map<TaskItemWorkerSelector, Integer> limits);

        /** Rechecks at most 100 held identities in total against the same Rule and queries. */
        Map<TaskItemWorkerSelector, Set<String>> retain(Map<TaskItemWorkerSelector, List<String>> held);
    }

    /** Takes up to 100 ordered identities and advances their owner-local rotation time. */
    List<String> takeWorkerIds(String workerGroupId, TaskItemWorkerSelector selector, int limit);

    /** Rechecks at most 100 held identities against the current index membership. */
    Set<String> retainWorkerIds(String workerGroupId, TaskItemWorkerSelector selector, List<String> workerIds);
}
