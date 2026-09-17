package com.xa.mass.workermatching.index;

import com.xa.mass.kernel.redis.RedisKeyspace;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.Objects;
import java.util.function.Supplier;

import io.lettuce.core.ScriptOutputType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Group-local property index; reverse membership and value sets share the Facts commit. */
public final class PhoneIndex {
    private static final String HELPERS = """
            local function check(key, expected)
              local kind=redis.call('TYPE',key).ok
              if kind~='none' and kind~=expected then error('corrupt phone index type') end
            end
            local function valueKey(key,phone)
              return key..':value:'..redis.sha1hex(phone)
            end
            """;
    private static final String PREPARE = HELPERS + """
            return function(key,id,w,p)
              check(key,'hash')
              local old=redis.call('HGET',key,id)
              if old and old=='' then error('corrupt phone reverse membership') end
              local next=type(w.phone)=='string' and w.phone~='' and w.phone or nil
              if old then check(valueKey(key,old),'set') end
              if next then check(valueKey(key,next),'set') end
              return function()
                if old and old~=next then redis.call('SREM',valueKey(key,old),id) end
                if next then
                  redis.call('HSET',key,id,next)
                  redis.call('SADD',valueKey(key,next),id)
                else redis.call('HDEL',key,id) end
              end
            end
            """;
    private static final String LOOKUP = HELPERS + """
            check(KEYS[1],'hash')
            local result={}
            for i=1,#ARGV,2 do
              local phone,count=ARGV[i],tonumber(ARGV[i+1])
              local key=valueKey(KEYS[1],phone)
              check(key,'set')
              local selected=redis.call('SRANDMEMBER',key,count)
              local rows={}
              for _,id in ipairs(selected) do
                if redis.call('HGET',KEYS[1],id)==phone then rows[#rows+1]=id end
              end
              result[#result+1]=rows
            end
            return result
            """;

    private final Supplier<RedisCommands<String, String>> commands;
    private final RedisKeyspace keyspace;
    public PhoneIndex(Supplier<RedisCommands<String, String>> commands, RedisKeyspace keyspace) {
        this.commands = Objects.requireNonNull(commands); this.keyspace = Objects.requireNonNull(keyspace);
    }

    public static IndexMutation mutation() {
        return new IndexMutation("phone", PREPARE);
    }

    public Map<String, List<String>> lookup(String group, Map<String, Integer> counts) {
        if (counts.isEmpty()) return Map.of();
        var args = new ArrayList<String>();
        int total = 0;
        for (var request : counts.entrySet()) {
            if (request.getKey() == null || request.getKey().isEmpty() || request.getValue() == null
                    || request.getValue() < 1 || request.getValue() > 100 - total)
                throw new IllegalArgumentException("phone requests require positive counts totaling at most 100");
            total += request.getValue();
            args.add(request.getKey()); args.add(Integer.toString(request.getValue()));
        }
        List<?> rows = commands.get().eval(LOOKUP, ScriptOutputType.MULTI,
                new String[]{IndexMutation.base(keyspace, group) + ":phone"}, args.toArray(String[]::new));
        var result = new LinkedHashMap<String, List<String>>();
        int row = 0;
        for (String phone : counts.keySet()) {
            result.put(phone, ((List<?>) rows.get(row++)).stream().map(String.class::cast).toList());
        }
        return Collections.unmodifiableMap(result);
    }
}
