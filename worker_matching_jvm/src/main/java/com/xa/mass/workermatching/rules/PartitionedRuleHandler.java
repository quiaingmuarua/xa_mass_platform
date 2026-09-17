package com.xa.mass.workermatching.rules;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import java.util.*;

/** Complete Eligibility implementation for the existing partitioned ZSET Rules. */
abstract class PartitionedRuleHandler extends PoolRule<PartitionedZsetIndex.Projection> {
    private final String namespace;
    PartitionedRuleHandler(RedisRuleStorage storage, String namespace) { super(storage); this.namespace = namespace; }
    abstract PartitionedZsetIndex.Criteria criteria(Map<String, List<String>> query);

    @Override protected EligibilityQuery normalize(String group, EligibilityQuery input) {
        var expression = input.query();
        if (expression.containsKey("workerId")) throw new IllegalArgumentException("workerId query requires worker.default");
        var query = RuleQueries.normalize(input); criteria(query.query()); return query;
    }
    @Override protected Selection target(String group, EligibilityQuery query) {
        var criteria = criteria(query.query());
        return selection(criteria);
    }
    protected static Selection selection(PartitionedZsetIndex.Criteria criteria) {
        if (criteria.partition().isEmpty())
            return criteria.kind().equals("any") ? all() : range("country", criteria.values());
        return criteria.kind().equals("any") ? range("partition:" + criteria.partition(), List.of("1"))
                : range("country-partition:" + criteria.partition(), criteria.values());
    }
    @Override protected Map<String, String> memberships(String group, String id, PartitionedZsetIndex.Projection projection) {
        if (projection == null) return null;
        var views = new LinkedHashMap<String, String>();
        views.put("country", projection.prefix());
        for (String partition : projection.partitions()) {
            views.put("partition:" + partition, "1");
            views.put("country-partition:" + partition, projection.prefix());
        }
        return views;
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
