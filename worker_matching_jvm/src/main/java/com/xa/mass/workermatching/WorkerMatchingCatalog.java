package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.assignment.WorkerMatching;
import com.xa.mass.kernel.assignment.WorkerQuery;
import java.util.List;
import java.util.Set;

/** Query and Pool supply admission alongside bounded Pacer operations. */
public interface WorkerMatchingCatalog extends WorkerMatching {
    /** Startup enablement only; does not read Facts, inspect stock or promise executable Workers. */
    Capabilities capabilities(String workerGroupId);

    record FunctionCapability(String name, @org.jspecify.annotations.Nullable String poolName) {}

    record Capabilities(Set<String> pools, List<FunctionCapability> functions) {
        public Capabilities {
            pools = java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(pools));
            functions = functions.stream().sorted(java.util.Comparator.comparing(FunctionCapability::name)).toList();
        }
    }

    /** Idempotent Server admission, without Redis reads or stock changes. */
    WorkerQuery normalizeQuery(String workerGroupId, WorkerQuery query);

    /** Normalizes explicit immutable supply, including an empty list, without defaults, Redis or stock access. */
    List<RefillTarget> normalizeRefill(String workerGroupId, List<RefillTarget> declarations);
}
