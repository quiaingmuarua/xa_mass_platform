package com.xa.mass.kernel.assignment;

import com.xa.mass.kernel.task.TaskItemWorkerSelector;
import java.util.List;
import java.util.Set;

/**
 * Matching-owned property selector interpretation and bounded identity evidence.
 * Callers handle ANY/explicit IDs themselves and pass other selectors unchanged.
 * Index evidence is not eligibility, a lease or a Candidate Cache.
 */
public interface WorkerCandidateIndex {
    /** Takes up to 100 ordered identities and advances their owner-local rotation time. */
    List<String> takeWorkerIds(String workerGroupId, TaskItemWorkerSelector selector, int limit);

    /** Rechecks at most 100 held identities against the current index membership. */
    Set<String> retainWorkerIds(String workerGroupId, TaskItemWorkerSelector selector, List<String> workerIds);
}
