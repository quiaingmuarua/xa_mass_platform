package com.xa.mass.server.testsupport;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.pool.WorkerCandidatePool;
import com.xa.mass.workermatching.refill.PoolMaintenance;
import com.xa.mass.workermatching.storage.FactsIndexStore;
import com.xa.mass.workermatching.QueryFunction;
import java.util.*;
import java.util.function.*;

/** Test Rule qualifying only the supplied Worker/Platform Facts snapshot. */
public final class BucketPoolFixture extends PoolMaintenance<String> {
    public static final String ID="proof.bucket";
    private final boolean failSnapshot;
    private final WorkerCandidatePool stock;
    private final FactsIndexStore storage;
    public BucketPoolFixture(LongSupplier clock, FactsIndexStore storage, WorkerCandidatePool stock, boolean failSnapshot) {
        super(stock); this.storage=storage; this.stock=stock; this.failSnapshot=failSnapshot;
    }
    public QueryFunction functions() {
            return new QueryFunction() {
                public Object normalizeInput(String group, Object input) { return normalizeLocalInput(group, input); }
                public Map<String, WorkerCandidate> apply(String group, Map<String, Object> inputs) {
                    var grouped = new LinkedHashMap<EligibilityQuery, List<String>>();
                    inputs.forEach((id, input) -> grouped.computeIfAbsent(EligibilityQuery.parse((Map<?, ?>) input), ignored -> new ArrayList<>()).add(id));
                    var assigned = new HashMap<String, WorkerCandidate>();
                    grouped.forEach((query, ids) -> {
                        var candidates = query.query().isEmpty() ? stock.pollAnyBatch(group, ids.size())
                                : stock.pollBatch(group, Set.copyOf(query.query().get("test.bucket")), ids.size());
                        for (int i = 0; i < candidates.size(); i++) assigned.put(ids.get(i), candidates.get(i));
                    });
                    var result = new LinkedHashMap<String, WorkerCandidate>();
                    inputs.keySet().forEach(id -> { if (assigned.containsKey(id)) result.put(id, assigned.get(id)); });
                    return Collections.unmodifiableMap(result);
                }
            };
        }
    @Override protected EligibilityQuery normalize(String group,EligibilityQuery input) {
            var expression = input.query();
        if(!Set.of("test.bucket").containsAll(expression.keySet()))throw new IllegalArgumentException("unsupported bucket parameter");
        if(expression.isEmpty())return new EligibilityQuery(Map.of());
        if(!(expression.get("test.bucket") instanceof List<?> values)
                || values.stream().anyMatch(v->!(v instanceof String)))throw new IllegalArgumentException("bucket requires string parameters");
        return new EligibilityQuery(Map.of("test.bucket",List.copyOf(new TreeSet<>(values.stream().map(String.class::cast).toList()))));
    }
    @Override protected Map<EligibilityQuery, Set<String>> matchingKeys(String group,
                Collection<EligibilityQuery> queries, Set<String> keys) {
            var result = new LinkedHashMap<EligibilityQuery, Set<String>>();
            for (var query : queries) {

                if (query.query().isEmpty()) result.put(query, keys);
                else {
                    var selected = new HashSet<>(query.query().get("test.bucket"));
                    selected.retainAll(keys); result.put(query, selected);
                }
            }
            return result;
        }
    private Object normalizeLocalInput(String group,Object input) {
        return normalize(group,EligibilityQuery.parse((Map<?,?>)input)).query();
    }

    @Override protected String bucketKey(String group,String id,String bucket) {

            return bucket;
        }
    @Override protected Map<String,String> readQualifications(String group,List<String> ids) {
        if(failSnapshot)throw new IllegalStateException("injected bucket qualification failure");
        var result=new LinkedHashMap<String,String>();
        for (var facts : storage.readFactsSnapshot(group, ids).values()) {
            Object bucket = facts.workerProperties().get("testBucket");
            if (bucket instanceof String value && !value.isEmpty()
                    && !"no".equals(facts.platformProperties().get("testEnabled")))
                result.put(facts.workerId(), value);
        }
        return result;
    }
}
