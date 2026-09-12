package com.xa.mass.kernel.assignment;

import com.xa.mass.kernel.task.TaskItemWorkerSelector;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Matching-owned property selector interpretation and bounded identity evidence.
 * Every Task has a Matching binding. Index evidence establishes neither HOT availability nor a lease.
 */
public interface WorkerCandidateIndex {
    /** One bounded read for at most 100 Task/Group coordinates. Unusable bindings map to null. */
    Map<String, @Nullable TaskQuery> prepareTaskQueries(Map<String, String> taskGroups);

    /** One dispatch-local query; owns no cache, thread or close lifecycle. */
    interface TaskQuery {
        /** True only when this query permits Kernel's unconstrained ANY/explicit identity selection. */
        boolean usesIdentitySelection(TaskItemWorkerSelector selector);

        /** Admission and dispatch use the same bound query semantics; performs no Redis read. */
        void validate(TaskItemWorkerSelector selector);

        /** At most 100 queries, requesting at most 100 identities in total. */
        Map<TaskItemWorkerSelector, List<String>> take(Map<TaskItemWorkerSelector, Integer> limits);

        /** Rechecks at most 100 held identities against the prepared queries and any Task binding. */
        Map<TaskItemWorkerSelector, Set<String>> retain(Map<TaskItemWorkerSelector, List<String>> held);
    }
}
