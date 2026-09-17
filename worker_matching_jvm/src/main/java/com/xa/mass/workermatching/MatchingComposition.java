package com.xa.mass.workermatching;

import com.xa.mass.workermatching.rules.*;
import java.util.*;

/** Fixed production resource wiring. No lookup, lifecycle or dynamic registration protocol. */
public final class MatchingComposition {
    private final Map<String,CandidatePool> pools;
    private final Map<String,PoolRefillPolicy> policies;
    private final Map<String,QueryFunctions> functions;
    private final Map<String,List<MatchingStorage.IndexMutation>> indexes;
    public MatchingComposition(MatchingStorage storage, Map<String,MatchingGroup> groups) {
        var countryGroups = new LinkedHashSet<String>();
        groups.forEach((group, config) -> { if (config.pools().contains("country")) countryGroups.add(group); });
        var defaults = new CandidatePool(storage);
        var country = new CandidatePool(storage);
        var messaging = new CandidatePool(storage);
        var proof = new CandidatePool(storage);
        Map<String,PoolRefillPolicy> policies = Map.of(
                "default", new DefaultPoolPolicy(storage, defaults, countryGroups),
                "country", new CountryPoolPolicy(storage, country),
                "messaging", new MessagingPoolPolicy(storage, messaging),
                "proof-facts", new ProofFactsPoolPolicy(storage, proof));
        var functions = Map.of(
                "worker.default", PoolQueryFunctions.defaults(defaults, countryGroups),
                "worker.country", PoolQueryFunctions.country(country),
                "worker.messaging.available", PoolQueryFunctions.messaging(messaging),
                "proof.worker.facts", PoolQueryFunctions.proofFacts(proof),
                "workerId", DirectQueryFunctions.identity(),
                "worker.phone", new DirectQueryFunctions(storage).phone());
        var dependencies = Map.of("worker.country", "country", "worker.messaging.available", "messaging",
                "proof.worker.facts", "proof-facts");
        var indexes = new LinkedHashMap<String,List<MatchingStorage.IndexMutation>>();
        groups.forEach((group, config) -> {
            for (String name : config.functions()) {
                String required = dependencies.get(name);
                if (required != null && !config.pools().contains(required))
                    throw new IllegalArgumentException("Function " + name + " requires Pool " + required);
            }
            var resources = new ArrayList<MatchingStorage.IndexMutation>();
            if (config.pools().contains("country")) resources.add(CountryPoolPolicy.index());
            if (config.pools().contains("messaging")) resources.add(MessagingPoolPolicy.index());
            if (config.pools().contains("proof-facts")) resources.add(ProofFactsPoolPolicy.index());
            if (config.functions().contains("worker.phone")) resources.add(PhoneIndex.mutation());
            indexes.put(group,List.copyOf(resources));
        });
        this.pools = Map.of("default",defaults,"country",country,"messaging",messaging,"proof-facts",proof);
        this.policies = Map.copyOf(policies); this.functions = Map.copyOf(functions); this.indexes = Map.copyOf(indexes);
    }
    public Map<String,CandidatePool> pools() { return pools; }
    public Map<String,PoolRefillPolicy> policies() { return policies; }
    public Map<String,QueryFunctions> functions() { return functions; }
    public Map<String,List<MatchingStorage.IndexMutation>> indexes() { return indexes; }
    public static RedisWorkerMatchingCatalog create(MatchingStorage storage, Map<String,MatchingGroup> groups) {
        var composition = new MatchingComposition(storage,groups);
        return new RedisWorkerMatchingCatalog(storage,composition.policies,composition.functions,groups,composition.indexes);
    }
}
