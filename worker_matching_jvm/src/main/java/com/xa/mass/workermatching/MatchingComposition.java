package com.xa.mass.workermatching;

import com.xa.mass.workermatching.functions.PhoneQueryFunction;
import com.xa.mass.workermatching.functions.IdentityQueryFunction;
import com.xa.mass.workermatching.functions.ProofFactsQueryFunction;
import com.xa.mass.workermatching.functions.MessagingQueryFunction;
import com.xa.mass.workermatching.functions.MessagingPhoneQueryFunction;
import com.xa.mass.workermatching.functions.CountryQueryFunction;
import com.xa.mass.workermatching.functions.AnyQueryFunction;
import com.xa.mass.workermatching.functions.AssignmentWindowQueryFunction;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.workermatching.index.RedisHashPropertyIndex;
import com.xa.mass.workermatching.pool.CandidateBudget;
import com.xa.mass.workermatching.pool.WorkerCandidatePool;
import com.xa.mass.workermatching.refill.AnyPoolPolicy;
import com.xa.mass.workermatching.refill.CountryPoolPolicy;
import com.xa.mass.workermatching.refill.MessagingPoolPolicy;
import com.xa.mass.workermatching.refill.ProofFactsPoolPolicy;
import com.xa.mass.workermatching.storage.FactsIndexStore;
import io.lettuce.core.RedisClient;
import java.util.*;
import java.util.function.LongSupplier;

/** Fixed resource wiring; resource construction and startup precede every Pacer caller. */
public final class MatchingComposition implements AutoCloseable {
    private final FactsIndexStore storage;
    private final LongSupplier clock;
    private final CandidateBudget budget = new CandidateBudget();
    private final Map<String, MatchingGroup> groups;
    private final Map<String, WorkerCandidatePool> pools;
    private final Map<String, PoolRefillPolicy> policies;
    private final Map<String, QueryFunction> functions;
    private final List<String> poolOrder;
    private final Set<String> globalFunctions = Set.of("workerId");
    private final DefaultWorkerMatchingCatalog catalog;
    private boolean closed;

    public MatchingComposition(FactsIndexStore storage, Map<String, MatchingGroup> groups, LongSupplier clock) {
        this.storage = Objects.requireNonNull(storage);
        try {
            this.clock = Objects.requireNonNull(clock);
            this.groups = Map.copyOf(groups);
            var enabledPools = new HashSet<String>();
            var enabledFunctions = new HashSet<String>();
            var windows = new LinkedHashMap<String, MatchingGroup.AssignmentWindow>();
            var dependencies = Map.of("worker.any", "any", "worker.assignment.available", "any", "worker.country", "country",
                    "worker.messaging.available", "messaging", "proof.worker.facts", "proof-facts");
            groups.forEach((group, config) -> {
                if (config.functions().contains("worker.assignment.available")) {
                    if (config.assignmentWindow() == null)
                        throw new IllegalArgumentException("Assignment window required for Group " + group);
                    windows.put(group, config.assignmentWindow());
                }
                for (String name : config.functions()) {
                    String required = dependencies.get(name);
                    if (required != null && !config.pools().contains(required))
                        throw new IllegalArgumentException("Function " + name + " requires Pool " + required);
                }
                enabledPools.addAll(config.pools());
                enabledFunctions.addAll(config.functions());
            });
            var pools = new LinkedHashMap<String, WorkerCandidatePool>();
            var policies = new LinkedHashMap<String, PoolRefillPolicy>();
            var functions = new LinkedHashMap<String, QueryFunction>();
            functions.put("workerId", new IdentityQueryFunction());
            for (String name : List.of("any", "country", "messaging", "proof-facts")) {
                if (!enabledPools.contains(name)) continue;
                var pool = new WorkerCandidatePool(clock, budget);
                pools.put(name, pool);
                switch (name) {
                    case "any" -> {
                        policies.put(name, new AnyPoolPolicy(pool));
                        functions.put("worker.any", new AnyQueryFunction(pool));
                    }
                    case "country" -> {
                        policies.put(name, new CountryPoolPolicy(pool, storage::readWorkerFacts));
                        functions.put("worker.country", new CountryQueryFunction(pool));
                    }
                    case "messaging" -> {
                        policies.put(name, new MessagingPoolPolicy(pool, storage::readWorkerFacts));
                        functions.put("worker.messaging.available", new MessagingQueryFunction(pool));
                    }
                    case "proof-facts" -> {
                        policies.put(name, new ProofFactsPoolPolicy(pool, storage::readFactsSnapshot));
                        functions.put("proof.worker.facts", new ProofFactsQueryFunction(pool));
                    }
                    default -> throw new IllegalStateException("Unexpected built-in Pool");
                }
            }
            if (!windows.isEmpty()) functions.put("worker.assignment.available",
                    new AssignmentWindowQueryFunction(pools.get("any"), storage::readFactsSnapshot, clock, windows));
            if (enabledFunctions.contains("worker.phone") || enabledFunctions.contains("worker.messaging.phone")) {
                var phone = new RedisHashPropertyIndex(storage::commands, storage.keyspace(), "phone");
                if (enabledFunctions.contains("worker.phone")) functions.put("worker.phone", new PhoneQueryFunction(phone));
                if (enabledFunctions.contains("worker.messaging.phone"))
                    functions.put("worker.messaging.phone", new MessagingPhoneQueryFunction(phone, storage::readWorkerFacts));
            }
            this.pools = Map.copyOf(pools);
            this.policies = Map.copyOf(policies);
            this.functions = Map.copyOf(functions);
            this.poolOrder = List.of("proof-facts", "country", "any", "messaging").stream()
                    .filter(policies::containsKey).toList();
            if (!groups.keySet().containsAll(storage.indexedGroups()))
                throw new IllegalArgumentException("Index Group unavailable");
            this.catalog = new DefaultWorkerMatchingCatalog(budget, this.pools, clock, this.policies,
                    this.functions, this.groups, poolOrder, globalFunctions);
        } catch (RuntimeException | Error failure) {
            try { storage.close(); }
            catch (RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    /** Fixed dependencies, independent of Task demand or current Pool inventory. */
    public static Map<String, Set<String>> indexedProperties(Map<String, MatchingGroup> groups) {
        var indexes = new LinkedHashMap<String, Set<String>>();
        groups.forEach((group, config) -> {
            boolean phone = config.functions().contains("worker.phone") || config.functions().contains("worker.messaging.phone");
            indexes.put(group, phone ? Set.of("phone") : Set.of());
        });
        return Collections.unmodifiableMap(indexes);
    }

    public Map<String, WorkerCandidatePool> pools() { return pools; }
    public CandidateBudget budget() { return budget; }
    public Map<String, PoolRefillPolicy> policies() { return policies; }
    public Map<String, QueryFunction> functions() { return functions; }
    public List<String> poolOrder() { return poolOrder; }
    public Set<String> globalFunctions() { return globalFunctions; }

    public DefaultWorkerMatchingCatalog catalog() {
        return catalog;
    }

    public WorkerProperties properties() { return storage; }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        System.getLogger(getClass().getName()).log(System.Logger.Level.INFO, "Matching stopped " + budget.diagnostics());
        storage.close();
    }

    public static MatchingComposition create(RedisClient client, RedisKeyspace keyspace,
            Map<String, MatchingGroup> groups) {
        var storage = new FactsIndexStore(client, keyspace, indexedProperties(groups));
        return new MatchingComposition(storage, groups, System::currentTimeMillis);
    }
}
