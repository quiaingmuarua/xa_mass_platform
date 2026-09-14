package com.xa.mass.workermatching.rules;

import com.xa.mass.workermatching.*;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.*;
import java.util.function.*;

/** Optional implementation shared by the existing partitioned ZSET Rules. */
abstract class PartitionedRuleHandler implements RuleHandler {
    private final String namespace;
    private final String projection;
    PartitionedRuleHandler(String namespace,String projection) { this.namespace=namespace; this.projection=projection; }
    abstract PartitionedZsetIndex.Criteria criteria(Map<String,List<String>> query);
    @Override public List<IndexMutation> indexes() { return List.of(new IndexMutation(namespace,ZsetProjection.prepare(projection))); }
    @Override public Bound bind(Supplier<RedisCommands<String,String>> commands,String base,Set<String> enabled) {
        var source=new PartitionedZsetIndex(commands,base+":"+namespace);
        return new Bound() {
            @Override public EligibilityQuery normalize(Map<String,?> expression,int count) {
                if(expression.containsKey("workerId"))throw new IllegalArgumentException("workerId query requires worker.default");
                var query=RuleQueries.normalize(expression,count); criteria(query.query()); return query;
            }
            @Override public EligibilityQuery selector(Map<String,?> expression,int count) {
                RuleQueries.requireConditions(expression);
                return normalize(expression,count);
            }
            @Override public Query compile(EligibilityQuery query) {
                var criteria=criteria(query.query());
                return member -> member.projection() instanceof PartitionedZsetIndex.Projection projection
                        && projection.matches(member.workerId(),criteria);
            }
            @Override public Map<String,Member> snapshot(List<String> ids) {
                var result=new LinkedHashMap<String,Member>();
                source.snapshot(ids).forEach((id,projection)->result.put(id,new Member(id,projection)));
                return result;
            }
        };
    }
    static PartitionedZsetIndex.Criteria countries(Map<String,List<String>> query,Set<String> supported,String partition) {
        if(!supported.containsAll(query.keySet()))throw new IllegalArgumentException("unsupported Rule condition");
        if(!query.containsKey("worker.country"))return new PartitionedZsetIndex.Criteria(partition,"any",List.of());
        var codes=query.get("worker.country").stream().map(v->Integer.toString(CountryIndex.code(v))).distinct().toList();
        return new PartitionedZsetIndex.Criteria(partition,"countries",codes);
    }
}
