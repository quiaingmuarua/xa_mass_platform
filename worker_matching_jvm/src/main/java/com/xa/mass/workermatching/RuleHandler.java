package com.xa.mass.workermatching;

import io.lettuce.core.api.sync.RedisCommands;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Composition-time Rule policy. The Catalog owns connections, shared stock and lifecycle. */
public interface RuleHandler {
    /** Bind lightweight functions to one Group; do not allocate connection or inventory owners. */
    Bound bind(Supplier<RedisCommands<String, String>> commands, String indexBase, Set<String> enabledRules);

    /** Exclusive index roots, including descendants, used by facts updates and startup rebuild. */
    default List<IndexMutation> indexes() { return List.of(); }

    /**
     * Trusted Lua source returns prepare(key, workerId, workerFacts, platformFacts). Prepare validates
     * without writing and returns an apply() closure. The Owner prepares all indexes and Workers before
     * any write. The program is application code, never an external query or persisted DSL.
     */
    record IndexMutation(String namespace, String prepareLua) {
        public IndexMutation {
            if (namespace == null || !namespace.matches("[A-Za-z0-9_-]+")
                    || prepareLua == null || prepareLua.isBlank()) {
                throw new IllegalArgumentException("Rule index requires a safe namespace and preparation program");
            }
        }
    }

    /** Immutable Rule-owned projection; null denotes missing membership. */
    record Member(String workerId, Object projection) { }

    interface Query {
        boolean matches(Member member);
        default int target(int requested) { return requested; }
    }

    /** Paired functions used by the same shared deficits/refill/take implementation. */
    interface Bound {
        EligibilityQuery normalize(Map<String, ?> expression, int count);
        /** HTTP admission may retain a different wire syntax while compiling to the same query. */
        default EligibilityQuery selector(Map<String, ?> expression, int count) { return normalize(expression,count); }
        Query compile(EligibilityQuery query);

        /** Only the supplied post-hold identities, in one batch. No discovery or lease operations. */
        Map<String, Member> snapshot(List<String> workerIds);
    }
}
