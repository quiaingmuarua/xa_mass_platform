package com.xa.mass.workermatching.rules;


import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.*;
import java.util.function.Supplier;

/** Bounded query mechanics shared by explicitly composed Rules; coordinates never leave Matching. */
final class PartitionedZsetIndex {
    record Criteria(String partition, String kind, List<String> values) { }
    // Shared metadata validation for facts projection and bounded post-hold reads.
    static final String PARTITIONS_LUA = """
            local function partitions(key,id)
              local raw=redis.call('HGET',key..':partitions',id)
              if not raw then return {} end
              if not string.match(raw,'^%s*%[') then error('corrupt Rule partitions') end
              local values=cjson.decode(raw)
              if type(values)~='table' or #values>9 then error('corrupt Rule partitions') end
              local count,seen=0,{}
              for k,v in pairs(values) do
                count=count+1
                if type(k)~='number' or k<1 or k>#values or k~=math.floor(k) or
                  type(v)~='string' or v=='' or seen[v] then error('corrupt Rule partitions') end
                seen[v]=true
              end
              if count~=#values then error('corrupt Rule partitions') end
              return values
            end
            local function checkPartitionKeys(key,parts)
              for _,suffix in ipairs(parts) do
                local kind=redis.call('TYPE',key..':partition:'..redis.sha1hex(suffix)).ok
                if kind~='none' and kind~='zset' then error('corrupt Rule partition index') end
              end
            end
            """;
    private static final String SNAPSHOT = PARTITIONS_LUA + """
            local scores=redis.call('ZMSCORE',KEYS[1],unpack(ARGV))
            local result={}
            for i,raw in ipairs(scores) do
              if not raw then result[i]={} else
                local score=tonumber(raw)
                if not score or score<0 or score>=676*8796093022208 or score~=math.floor(score) then
                  return redis.error_reply('corrupt Rule index score')
                end
                local parts=partitions(KEYS[1],ARGV[i])
                checkPartitionKeys(KEYS[1],parts)
                result[i]={tostring(math.floor(score/8796093022208)),parts}
              end
            end
            return result
            """;

    record Projection(String prefix, Set<String> partitions) {
        boolean matches(String id, Criteria criteria) {
            return (criteria.partition().isEmpty() || partitions.contains(criteria.partition()))
                    && switch (criteria.kind()) {
                        case "any" -> true;
                        case "countries" -> criteria.values().contains(prefix);
                        default -> throw new IllegalArgumentException("unknown criteria");
                    };
        }
    }

    private final Supplier<RedisCommands<String,String>> commands;
    private final String key;

    PartitionedZsetIndex(Supplier<RedisCommands<String,String>> commands, String key) {
        this.commands=commands; this.key=key;
    }

    /** Recheck membership and obtain the entire query projection after acquisition established the initial soft hold. */
    Map<String,Projection> snapshot(List<String> ids) {
        if (ids.isEmpty()) return Map.of();
        if (ids.size()>100) throw new IllegalArgumentException("at most 100 identities");
        List<?> rows=commands.get().eval(SNAPSHOT,ScriptOutputType.MULTI,new String[]{key,key+":partitions"},ids.toArray(String[]::new));
        var result=new LinkedHashMap<String,Projection>();
        for (int i=0;i<ids.size();i++) {
            var row=(List<?>)rows.get(i);
            if (!row.isEmpty()) result.put(ids.get(i),new Projection((String)row.getFirst(),Set.copyOf(strings(row.get(1)))));
        }
        return result;
    }

    private static List<String> strings(Object row) {
        return ((List<?>) row).stream().map(String.class::cast).toList();
    }
}
