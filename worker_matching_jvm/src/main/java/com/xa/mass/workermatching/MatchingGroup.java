package com.xa.mass.workermatching;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Construction-time availability of Pool resources and independently named Item functions. */
public record MatchingGroup(Set<String> pools, Set<String> functions, @Nullable AssignmentWindowPool assignmentWindowPool) {
    /** Qualification of the independent assignment-window Pool; not an execution quota. */
    public record AssignmentWindowPool(long windowMillis, long maxAssignments) {
        public AssignmentWindowPool {
            if (windowMillis <= 0 || maxAssignments <= 0)
                throw new IllegalArgumentException("assignment window and threshold must be positive");
        }
    }

    public MatchingGroup {
        pools = pools == null ? Set.of() : Set.copyOf(pools);
        functions = functions == null ? Set.of() : Set.copyOf(functions);
        for (String name : pools) if (name.isBlank()) throw new IllegalArgumentException("blank poolName");
        for (String name : functions) if (name.isBlank()) throw new IllegalArgumentException("blank executorName");
        if (pools.contains("assignment-window") != (assignmentWindowPool != null))
            throw new IllegalArgumentException("assignment-window Pool and its configuration must be enabled together");
    }
}
