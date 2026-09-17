package com.xa.mass.workermatching;

import com.xa.mass.workermatching.functions.PhoneQueryFunction;
import com.xa.mass.workermatching.functions.IdentityQueryFunction;
import com.xa.mass.workermatching.functions.ProofFactsQueryFunction;
import com.xa.mass.workermatching.functions.MessagingQueryFunction;
import com.xa.mass.workermatching.functions.CountryQueryFunction;
import com.xa.mass.workermatching.functions.AnyQueryFunction;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.workermatching.index.IndexMutation;
import com.xa.mass.workermatching.index.MessagingIndex;
import com.xa.mass.workermatching.index.PhoneIndex;
import com.xa.mass.workermatching.index.ProofFactsIndex;
import com.xa.mass.workermatching.pool.CandidateBudget;
import com.xa.mass.workermatching.pool.CandidatePool;
import com.xa.mass.workermatching.refill.AnyPoolPolicy;
import com.xa.mass.workermatching.refill.CountryPoolPolicy;
import com.xa.mass.workermatching.refill.MessagingPoolPolicy;
import com.xa.mass.workermatching.refill.ProofFactsPoolPolicy;
import com.xa.mass.workermatching.storage.FactsIndexStore;
import io.lettuce.core.RedisClient;
import java.util.*;
import java.util.function.LongSupplier;

/** Fixed resource wiring; resource construction and startup precede every Pacer caller. */
public final class MatchingComposition {
    private final FactsIndexStore storage;
    private final LongSupplier clock;
    private final CandidateBudget budget = new CandidateBudget();
    private final Map<String, MatchingGroup> groups;
    private final Map<String, CandidatePool> pools;
    private final Map<String, PoolRefillPolicy> policies;
    private final Map<String, QueryFunction> functions;

    public MatchingComposition(FactsIndexStore storage, Map<String, MatchingGroup> groups, LongSupplier clock) {
        this.storage = Objects.requireNonNull(storage);
        this.clock = Objects.requireNonNull(clock);
        this.groups = Map.copyOf(groups);
        var enabledPools = new HashSet<String>();
        var enabledFunctions = new HashSet<String>();
        var dependencies = Map.of("worker.any", "any", "worker.country", "country",
                "worker.messaging.available", "messaging", "proof.worker.facts", "proof-facts");
        groups.values().forEach(config -> {
            for (String name : config.functions()) {
                String required = dependencies.get(name);
                if (required != null && !config.pools().contains(required))
                    throw new IllegalArgumentException("Function " + name + " requires Pool " + required);
            }
            enabledPools.addAll(config.pools());
            enabledFunctions.addAll(config.functions());
        });
        var pools = new LinkedHashMap<String, CandidatePool>();
        var policies = new LinkedHashMap<String, PoolRefillPolicy>();
        var functions = new LinkedHashMap<String, QueryFunction>();
        functions.put("workerId", new IdentityQueryFunction());
        for (String name : List.of("any", "country", "messaging", "proof-facts")) {
            if (!enabledPools.contains(name)) continue;
            var pool = new CandidatePool(clock, budget);
            pools.put(name, pool);
            switch (name) {
                case "any" -> {
                    policies.put(name, new AnyPoolPolicy(clock, pool));
                    functions.put("worker.any", new AnyQueryFunction(pool));
                }
                case "country" -> {
                    policies.put(name, new CountryPoolPolicy(clock, pool, storage::readWorkerFacts));
                    functions.put("worker.country", new CountryQueryFunction(pool));
                }
                case "messaging" -> {
                    var index = new MessagingIndex(storage::commands, storage.keyspace());
                    policies.put(name, new MessagingPoolPolicy(clock, pool, index));
                    functions.put("worker.messaging.available", new MessagingQueryFunction(pool));
                }
                case "proof-facts" -> {
                    var index = new ProofFactsIndex(storage::commands, storage.keyspace());
                    policies.put(name, new ProofFactsPoolPolicy(clock, pool, index));
                    functions.put("proof.worker.facts", new ProofFactsQueryFunction(pool));
                }
                default -> throw new IllegalStateException("Unexpected built-in Pool");
            }
        }
        if (enabledFunctions.contains("worker.phone")) {
            var phone = new PhoneIndex(storage::commands, storage.keyspace());
            functions.put("worker.phone", new PhoneQueryFunction(phone));
        }
        this.pools = Map.copyOf(pools);
        this.policies = Map.copyOf(policies);
        this.functions = Map.copyOf(functions);
    }

    /** Fixed dependencies, independent of Task demand or current Pool inventory. */
    public static Map<String, List<IndexMutation>> indexes(Map<String, MatchingGroup> groups) {
        var indexes = new LinkedHashMap<String, List<IndexMutation>>();
        groups.forEach((group, config) -> {
            var resources = new ArrayList<IndexMutation>();
            if (config.pools().contains("messaging")) resources.add(MessagingIndex.mutation());
            if (config.pools().contains("proof-facts")) resources.add(ProofFactsIndex.mutation());
            if (config.functions().contains("worker.phone")) resources.add(PhoneIndex.mutation());
            indexes.put(group, List.copyOf(resources));
        });
        return Collections.unmodifiableMap(indexes);
    }

    public Map<String, CandidatePool> pools() { return pools; }
    public CandidateBudget budget() { return budget; }
    public Map<String, PoolRefillPolicy> policies() { return policies; }
    public Map<String, QueryFunction> functions() { return functions; }

    public RedisWorkerMatchingCatalog catalog() {
        return new RedisWorkerMatchingCatalog(storage, budget, pools, clock, policies, functions, groups);
    }

    public static RedisWorkerMatchingCatalog create(RedisClient client, RedisKeyspace keyspace,
            Map<String, MatchingGroup> groups) {
        var storage = new FactsIndexStore(client, keyspace, indexes(groups));
        try {
            var composition = new MatchingComposition(storage, groups, System::currentTimeMillis);
            var catalog = composition.catalog();
            storage.rebuildIndexes();
            return catalog;
        } catch (RuntimeException | Error failure) {
            try { storage.close(); }
            catch (RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }
}
