package com.xa.mass.server.testsupport;

import com.xa.mass.workermatching.EligibilityQuery;
import com.xa.mass.workermatching.RuleHandler;
import com.xa.mass.workerdelivery.json.Jsons;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.*;
import java.util.function.*;

/** Extension proof using only the public Matching contract: bucket SETs plus a projection HASH. */
public final class BucketRuleHandler implements RuleHandler {
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
    public BucketRuleHandler() { this(false); }
    public BucketRuleHandler(boolean failSnapshot) { this.failSnapshot=failSnapshot; }
    @Override public List<IndexMutation> indexes() { return List.of(new IndexMutation("test_buckets",PREPARE)); }
    @Override public Bound bind(Supplier<RedisCommands<String,String>> commands,String base,Set<String> enabled) {
        String key=base+":test_buckets";
        return new Bound() {
            public EligibilityQuery normalize(Map<String,?> expression,int count) {
                if(!Set.of("test.bucket").containsAll(expression.keySet()))throw new IllegalArgumentException("unsupported bucket parameter");
                if(expression.isEmpty())return new EligibilityQuery(Map.of(),count);
                if(!(expression.get("test.bucket") instanceof List<?> values)
                        || values.stream().anyMatch(v->!(v instanceof String)))throw new IllegalArgumentException("bucket requires string parameters");
                return new EligibilityQuery(Map.of("test.bucket",List.copyOf(new TreeSet<>(values.stream().map(String.class::cast).toList()))),count);
            }
            public Query compile(EligibilityQuery query) {
                return member->member.projection() instanceof String bucket &&
                        (query.query().isEmpty() || query.query().get("test.bucket").contains(bucket));
            }
            public Map<String,Member> snapshot(List<String> ids) {
                if(failSnapshot)throw new IllegalStateException("injected bucket projection failure");
                var result=new LinkedHashMap<String,Member>();
                for(var row:commands.get().hmget(key,ids.toArray(String[]::new))) {
                    if(row.hasValue())result.put(row.getKey(),new Member(row.getKey(),decode(row.getValue())));
                }
                return result;
            }
        };
    }
    private static String decode(String raw) {
        var value=Jsons.parseObject(raw);
        if(!value.keySet().equals(Set.of("bucket")) || !(value.get("bucket") instanceof String bucket) || bucket.isBlank())
            throw new IllegalStateException("corrupt bucket projection");
        return bucket;
    }
}
