package com.xa.mass.workermatching;

import java.util.Set;

/** Construction-time availability of Pool resources and independently named Item functions. */
public record MatchingGroup(Set<String> pools, Set<String> functions) {
    public MatchingGroup {
        pools = pools == null ? Set.of() : Set.copyOf(pools);
        functions = functions == null ? Set.of() : Set.copyOf(functions);
        for (String name : pools) if (name.isBlank()) throw new IllegalArgumentException("blank poolName");
        for (String name : functions) if (name.isBlank()) throw new IllegalArgumentException("blank executorName");
    }
}
