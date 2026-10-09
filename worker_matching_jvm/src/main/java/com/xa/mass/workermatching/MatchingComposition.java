package com.xa.mass.workermatching;

import com.xa.mass.workermatching.functions.PhoneQueryFunction;
import com.xa.mass.workermatching.functions.IdentityQueryFunction;
import com.xa.mass.workermatching.functions.ProofFactsQueryFunction;
import com.xa.mass.workermatching.functions.QualifiedCountryQueryFunction;
import com.xa.mass.workermatching.functions.QualifiedCountryPhoneQueryFunction;
import com.xa.mass.workermatching.functions.CountryQueryFunction;
import com.xa.mass.workermatching.functions.EmptyInputPoolQueryFunction;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.workermatching.index.RedisHashPropertyIndex;
import com.xa.mass.workermatching.index.NetworkEvidenceTimestamps;
import com.xa.mass.workermatching.pool.CandidateBudget;
import com.xa.mass.workermatching.pool.WorkerCandidatePool;
import com.xa.mass.workermatching.refill.AnyPoolPolicy;
import com.xa.mass.workermatching.refill.FixedWindowPoolPolicy;
import com.xa.mass.workermatching.refill.CountryPoolPolicy;
import com.xa.mass.workermatching.refill.QualifiedCountryPoolPolicy;
import com.xa.mass.workermatching.refill.ProofFactsPoolPolicy;
import com.xa.mass.workermatching.storage.FactsIndexStore;
import io.lettuce.core.RedisClient;
import java.util.*;
import java.util.function.LongSupplier;

/** Fixed resource wiring; resource construction and startup precede every Pacer caller. */
public final class MatchingComposition implements AutoCloseable {
    private final FactsIndexStore storage;
    private final NetworkEvidenceTimestamps networkEvidenceTimestamps;
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

    public MatchingComposition(FactsIndexStore storage, Map<String, MatchingGroup> groups, LongSupplier clock,
            Collection<FixedWindowPoolDefinition> definitions,
            Collection<QualifiedCountryDefinition> qualifiedDefinitions) {
        this.storage = Objects.requireNonNull(storage);
        try {
            this.networkEvidenceTimestamps = new NetworkEvidenceTimestamps(storage::commands, storage.keyspace());
            this.clock = Objects.requireNonNull(clock);
            this.groups = Map.copyOf(groups);
            var enabledPools = new HashSet<String>();
            var enabledFunctions = new HashSet<String>();
            var builtInPools = List.of("any", "country", "proof-facts");
            var poolNames = new HashSet<>(builtInPools);
            var functionNames = new HashSet<>(Set.of("workerId", "worker.any", "worker.country",
                    "proof.worker.facts", "worker.phone"));
            var windows = new TreeMap<String, FixedWindowPoolDefinition>();
            var qualified = new TreeMap<String, QualifiedCountryDefinition>();
            var dependencies = new HashMap<>(Map.of("worker.any", "any", "worker.country", "country",
                    "proof.worker.facts", "proof-facts"));
            for (var definition : List.copyOf(definitions)) {
                if (!poolNames.add(definition.poolName()))
                    throw new IllegalArgumentException("duplicate or reserved Pool name: " + definition.poolName());
                windows.put(definition.poolName(), definition);
                if (!functionNames.add(definition.functionName()))
                    throw new IllegalArgumentException("duplicate or reserved function name: " + definition.functionName());
                if (!this.groups.keySet().containsAll(definition.limitsByGroup().keySet()))
                    throw new IllegalArgumentException("window configuration names an unknown Group");
                this.groups.forEach((group, config) -> {
                    if (config.pools().contains(definition.poolName()) != definition.limitsByGroup().containsKey(group))
                        throw new IllegalArgumentException("window Pool and its Group configuration must be enabled together");
                });
                dependencies.put(definition.functionName(), definition.poolName());
            }
            for (var definition : List.copyOf(qualifiedDefinitions)) {
                if (!poolNames.add(definition.poolName()))
                    throw new IllegalArgumentException("duplicate or reserved Pool name: " + definition.poolName());
                for (String name : List.of(definition.poolFunctionName(), definition.phoneFunctionName()))
                    if (!functionNames.add(name))
                        throw new IllegalArgumentException("duplicate or reserved function name: " + name);
                qualified.put(definition.poolName(), definition);
                dependencies.put(definition.poolFunctionName(), definition.poolName());
            }
            groups.forEach((group, config) -> {
                for (String name : config.pools())
                    if (!poolNames.contains(name)) throw new IllegalArgumentException("Unknown Pool: " + name);
                for (String name : config.functions()) {
                    if (!functionNames.contains(name)) throw new IllegalArgumentException("Unknown function: " + name);
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
            for (String name : builtInPools) {
                if (!enabledPools.contains(name)) continue;
                var pool = new WorkerCandidatePool(clock, budget);
                pools.put(name, pool);
                switch (name) {
                    case "any" -> {
                        policies.put(name, new AnyPoolPolicy(pool));
                        functions.put("worker.any", new EmptyInputPoolQueryFunction(pool));
                    }
                    case "country" -> {
                        policies.put(name, new CountryPoolPolicy(pool, storage::readWorkerFacts));
                        functions.put("worker.country", new CountryQueryFunction(pool));
                    }
                    case "proof-facts" -> {
                        policies.put(name, new ProofFactsPoolPolicy(pool, storage::readFactsSnapshot));
                        functions.put("proof.worker.facts", new ProofFactsQueryFunction(pool));
                    }
                    default -> throw new IllegalStateException("Unexpected built-in Pool");
                }
            }
            windows.forEach((name, definition) -> {
                if (!enabledPools.contains(name)) return;
                var pool = new WorkerCandidatePool(clock, budget);
                pools.put(name, pool);
                policies.put(name, new FixedWindowPoolPolicy(pool, storage::readFactsSnapshot, clock, definition));
                functions.put(definition.functionName(), new EmptyInputPoolQueryFunction(pool));
            });
            var qualifications = new HashMap<String, QualifiedCountryEligibility>();
            qualified.forEach((name, definition) -> {
                var eligibility = new QualifiedCountryEligibility(definition);
                qualifications.put(name, eligibility);
                if (!enabledPools.contains(name)) return;
                var pool = new WorkerCandidatePool(clock, budget);
                pools.put(name, pool);
                policies.put(name, new QualifiedCountryPoolPolicy(pool, storage::readWorkerFacts, eligibility));
                functions.put(definition.poolFunctionName(), new QualifiedCountryQueryFunction(pool));
            });
            if (enabledFunctions.contains("worker.phone") || qualified.values().stream()
                    .anyMatch(definition -> enabledFunctions.contains(definition.phoneFunctionName()))) {
                var phone = new RedisHashPropertyIndex(storage::commands, storage.keyspace(), "phone");
                if (enabledFunctions.contains("worker.phone")) functions.put("worker.phone", new PhoneQueryFunction(phone));
                qualified.forEach((name, definition) -> {
                    if (enabledFunctions.contains(definition.phoneFunctionName()))
                        functions.put(definition.phoneFunctionName(), new QualifiedCountryPhoneQueryFunction(
                                phone, storage::readWorkerFacts, qualifications.get(name)));
                });
            }
            this.pools = Map.copyOf(pools);
            this.policies = Map.copyOf(policies);
            this.functions = Map.copyOf(functions);
            var order = new ArrayList<>(List.of("proof-facts", "country"));
            order.addAll(windows.keySet());
            order.add("any");
            order.addAll(qualified.keySet());
            this.poolOrder = order.stream()
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
    public static Map<String, Set<String>> indexedProperties(Map<String, MatchingGroup> groups,
            Collection<QualifiedCountryDefinition> definitions) {
        var phoneFunctions = new HashSet<>(Set.of("worker.phone"));
        List.copyOf(definitions).forEach(definition -> phoneFunctions.add(definition.phoneFunctionName()));
        var indexes = new LinkedHashMap<String, Set<String>>();
        groups.forEach((group, config) -> {
            boolean phone = config.functions().stream().anyMatch(phoneFunctions::contains);
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

    public NetworkEvidenceTimestamps networkEvidenceTimestamps() { return networkEvidenceTimestamps; }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        System.getLogger(getClass().getName()).log(System.Logger.Level.INFO, "Matching stopped " + budget.diagnostics());
        storage.close();
    }

    public static MatchingComposition create(RedisClient client, RedisKeyspace keyspace,
            Map<String, MatchingGroup> groups, Collection<FixedWindowPoolDefinition> definitions,
            Collection<QualifiedCountryDefinition> qualifiedDefinitions) {
        var captured = List.copyOf(qualifiedDefinitions);
        var storage = new FactsIndexStore(client, keyspace, indexedProperties(groups, captured));
        return new MatchingComposition(storage, groups, System::currentTimeMillis, definitions, captured);
    }
}
