package com.xa.mass.server.testsupport;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.pool.CandidatePool;
import com.xa.mass.workermatching.refill.PoolMaintenance;
import com.xa.mass.workermatching.index.IndexMutation;
import com.xa.mass.workermatching.storage.FactsIndexStore;
import com.xa.mass.workermatching.QueryFunction;
import static com.xa.mass.workermatching.pool.CandidatePool.*;
import com.xa.mass.workerdelivery.json.Jsons;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.*;
import java.util.function.*;

/** Non-ZSET Rule: bucket SETs and a HASH source, with Rule-owned local Eligibility. */
public final class BucketPoolFixture extends PoolMaintenance<String> {
    public static final String ID="proof.bucket";
    private static final String PREPARE="""
            local function projection(raw)
              if not raw then return nil end
              local value=cjson.decode(raw)
              if type(value)~='table' or type(value.bucket)~='string' or value.bucket=='' then error('corrupt bucket projection') end
              local size=0; for _ in pairs(value) do size=size+1 end
              if size~=1 then error('corrupt bucket projection') end
              return value.bucket
            end
            local function bucketKey(root,bucket) return root..':bucket:'..redis.sha1hex(bucket) end
            return function(key,id,w,p)
              local old=projection(redis.call('HGET',key,id))
              local next=type(w.testBucket)=='string' and w.testBucket~='' and p.testEnabled~='no' and w.testBucket or nil
              for _,bucket in pairs({old=old,next=next}) do
                local kind=redis.call('TYPE',bucketKey(key,bucket)).ok
                if kind~='none' and kind~='set' then error('corrupt bucket set') end
              end
              local encoded=next and cjson.encode({bucket=next}) or nil
              return function()
                if old then redis.call('SREM',bucketKey(key,old),id) end
                if next then
                  redis.call('HSET',key,id,encoded); redis.call('SADD',bucketKey(key,next),id)
                else redis.call('HDEL',key,id) end
              end
            end
            """;
    private final boolean failSnapshot;
    private final CandidatePool stock;
    private final FactsIndexStore storage;
    public BucketPoolFixture(LongSupplier clock, FactsIndexStore storage, CandidatePool stock, boolean failSnapshot) {
        super(stock); this.storage=storage; this.stock=stock; this.failSnapshot=failSnapshot;
    }
    public QueryFunction functions() {
            return new QueryFunction() {
                public Object normalizeInput(String group, Object input) { return normalizeLocalInput(group, input); }
                public Map<String, WorkerCandidate> apply(String group, Map<String, Object> inputs) {
                    var grouped = new LinkedHashMap<Selection, List<String>>();
                    inputs.forEach((id, input) -> grouped.computeIfAbsent(select(group, input), ignored -> new ArrayList<>()).add(id));
                    var limits = new LinkedHashMap<Selection, Integer>();
                    grouped.forEach((selection, ids) -> limits.put(selection, ids.size()));
                    var taken = stock.take(group, limits);
                    var assigned = new HashMap<String, WorkerCandidate>();
                    grouped.forEach((selection, ids) -> {
                        var candidates = taken.get(selection);
                        for (int i = 0; i < candidates.size(); i++) assigned.put(ids.get(i), candidates.get(i));
                    });
                    var result = new LinkedHashMap<String, WorkerCandidate>();
                    inputs.keySet().forEach(id -> { if (assigned.containsKey(id)) result.put(id, assigned.get(id)); });
                    return Collections.unmodifiableMap(result);
                }
            };
        }
    public static List<IndexMutation> indexes() {
        return List.of(new IndexMutation("test_buckets",PREPARE));
    }
    @Override protected EligibilityQuery normalize(String group,EligibilityQuery input) {
            var expression = input.query();
        if(!Set.of("test.bucket").containsAll(expression.keySet()))throw new IllegalArgumentException("unsupported bucket parameter");
        if(expression.isEmpty())return new EligibilityQuery(Map.of());
        if(!(expression.get("test.bucket") instanceof List<?> values)
                || values.stream().anyMatch(v->!(v instanceof String)))throw new IllegalArgumentException("bucket requires string parameters");
        return new EligibilityQuery(Map.of("test.bucket",List.copyOf(new TreeSet<>(values.stream().map(String.class::cast).toList()))));
    }
    @Override protected Selection target(String group,EligibilityQuery query) {
        return query.query().isEmpty() ? all() : range("bucket",query.query().get("test.bucket"));
    }
    private Object normalizeLocalInput(String group,Object input) {
        return normalize(group,EligibilityQuery.parse((Map<?,?>)input)).query();
    }
    private Selection select(String group,Object input) {
        return target(group,EligibilityQuery.parse((Map<?,?>)input));
    }
    @Override protected Map<String,String> memberships(String group,String id,String bucket) {
        return bucket==null ? null : Map.of("bucket",bucket);
    }
    @Override protected Map<String,String> readQualifications(String group,List<String> ids) {
        if(failSnapshot)throw new IllegalStateException("injected bucket projection failure");
        var result=new LinkedHashMap<String,String>();
        for(var row:storage.commands().hmget(IndexMutation.base(storage.keyspace(),group)+":test_buckets",ids.toArray(String[]::new)))
            if(row.hasValue())result.put(row.getKey(),decode(row.getValue()));
        return result;
    }
    private static String decode(String raw) {
        var value=Jsons.parseObject(raw);
        if(!value.keySet().equals(Set.of("bucket")) || !(value.get("bucket") instanceof String bucket) || bucket.isBlank())
            throw new IllegalStateException("corrupt bucket projection");
        return bucket;
    }
}
