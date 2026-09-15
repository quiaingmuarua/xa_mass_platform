package com.xa.mass.workermatching.rules;

import com.xa.mass.workermatching.EligibilityQuery;
import java.util.*;
import java.util.function.BiPredicate;

/** Complete Eligibility implementation for the existing partitioned ZSET Rules. */
abstract class PartitionedRuleHandler extends LocalCandidateRule<PartitionedZsetIndex.Projection> {
    private final String namespace;
    PartitionedRuleHandler(RedisRuleStorage storage, String namespace) { super(storage); this.namespace = namespace; }
    abstract PartitionedZsetIndex.Criteria criteria(Map<String, List<String>> query);

    @Override protected EligibilityQuery normalize(String group, Map<String, ?> expression, int count, boolean selector) {
        if (expression.containsKey("workerId")) throw new IllegalArgumentException("workerId query requires worker.default");
        if (selector) RuleQueries.requireConditions(expression);
        var query = RuleQueries.normalize(expression, count); criteria(query.query()); return query;
    }
    @Override protected BiPredicate<String, PartitionedZsetIndex.Projection> predicate(String group, EligibilityQuery query) {
        var criteria = criteria(query.query());
        return (id, projection) -> projection != null && projection.matches(id, criteria);
    }
    @Override protected Map<String, PartitionedZsetIndex.Projection> readQualifications(String group, List<String> ids) {
        return new PartitionedZsetIndex(storage::commands, storage.indexKey(group, namespace)).snapshot(ids);
    }
    static PartitionedZsetIndex.Criteria countries(Map<String,List<String>> query,Set<String> supported,String partition) {
        if(!supported.containsAll(query.keySet()))throw new IllegalArgumentException("unsupported Rule condition");
        if(!query.containsKey("worker.country"))return new PartitionedZsetIndex.Criteria(partition,"any",List.of());
        var codes=query.get("worker.country").stream().map(v->Integer.toString(CountryIndex.code(v))).distinct().toList();
        return new PartitionedZsetIndex.Criteria(partition,"countries",codes);
    }
}
