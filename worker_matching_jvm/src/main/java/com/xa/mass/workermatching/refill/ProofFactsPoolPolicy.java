package com.xa.mass.workermatching.refill;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.workermatching.WorkerMatchingCatalog.WorkerFacts;
import com.xa.mass.workermatching.pool.WorkerCandidatePool;
import com.xa.mass.workermatching.buckets.ProofFactsBuckets;
import java.util.*;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/** Installed only in explicitly configured proof Groups. */
public final class ProofFactsPoolPolicy extends PoolMaintenance<WorkerFacts> {
    private static final Map<String, String> FIELDS = Map.of("worker.proofPool", "proofPool",
            "worker.proofTarget", "proofTarget", "platform.proofEnabled", "proofEnabled",
            "worker.convergenceSlot", "convergenceSlot");
    private final BiFunction<String, List<String>, Map<String, WorkerFacts>> readFacts;
    public ProofFactsPoolPolicy(WorkerCandidatePool pool, BiFunction<String, List<String>, Map<String, WorkerFacts>> readFacts) {
        super(pool); this.readFacts = Objects.requireNonNull(readFacts);
    }
    @Override protected EligibilityQuery normalize(String group, EligibilityQuery input) {
        if (!FIELDS.keySet().containsAll(input.query().keySet())
                || input.query().containsKey("worker.convergenceSlot") && input.query().size() != 1)
            throw new IllegalArgumentException("unsupported proof query");
        var query = RuleQueries.normalize(input);
        query.query().values().forEach(RuleQueries::one);
        return query;
    }
    @Override protected Map<String, WorkerFacts> readQualifications(String group, List<String> ids) {
        return readFacts.apply(group, ids);
    }
    @Override protected @Nullable String bucketKey(String group, String id, @Nullable WorkerFacts facts) {
        return ProofFactsBuckets.bucketKey(facts);
    }
    @Override protected Map<EligibilityQuery, Set<String>> matchingKeys(String group,
            Collection<EligibilityQuery> queries, Set<String> keys) {
        var directory = ProofFactsBuckets.decodeKeys(keys);
        var result = new LinkedHashMap<EligibilityQuery, Set<String>>();
        for (var query : queries) {
            var values = new LinkedHashMap<String, String>();
            query.query().forEach((field, value) -> values.put(FIELDS.get(field), RuleQueries.one(value)));
            result.put(query, ProofFactsBuckets.matchingKeys(values, directory));
        }
        return result;
    }
}
