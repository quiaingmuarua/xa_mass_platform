package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.WorkerCandidateIndex.TaskQuery;
import com.xa.mass.kernel.task.TaskItemWorkerSelector;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Fixed country Rule: facts materialization and queries share one index encoding. */
final class CountryRuleHandler {
    static final String ID = "worker.country";
    private CountryRuleHandler() { }

    // Format score arguments explicitly: Lua tostring loses digits in these coordinates.
    private static final String STORE_INDEXED_FACTS_SCRIPT = """
            local scale = 8796093022208
            local results = {}
            for i = 1, #ARGV, 3 do
              local id, replacement, code = ARGV[i], ARGV[i+1], tonumber(ARGV[i+2])
              local old = redis.call('HGET', KEYS[1], id)
              if old == replacement then
                results[#results+1] = 0
              else
                redis.call('HSET', KEYS[1], id, replacement)
                results[#results+1] = 1
              end
              if code < 0 then
                redis.call('ZREM', KEYS[2], id)
              else
                local prior = redis.call('ZSCORE', KEYS[2], id)
                if not prior or math.floor(tonumber(prior) / scale) ~= code then
                  local low = prior and (tonumber(prior) % scale) or 0
                  redis.call('ZADD', KEYS[2], string.format('%.0f', code * scale + low), id)
                end
              end
            end
            return results
            """;

    static final String REBUILD_INDEX_SCRIPT = """
            local args = {}
            for i = 1, #ARGV, 2 do
              local raw = redis.call('HGET', KEYS[1], ARGV[i])
              if raw == ARGV[i+1] then
                local facts = cjson.decode(raw)
                local country = facts['country']
                if type(country) == 'string' and string.match(country, '^[A-Z][A-Z]$') then
                  local code = (string.byte(country, 1) - 65) * 26 + string.byte(country, 2) - 65
                  args[#args+1] = string.format('%.0f', code * 8796093022208)
                  args[#args+1] = ARGV[i]
                end
              end
            end
            if #args > 0 then redis.call('ZADD', KEYS[2], unpack(args)) end
            return 1
            """;

    private static final String TAKE = """
            local scale = 8796093022208
            local clock = redis.call('TIME')
            local now = tonumber(clock[1])*1000 + math.floor(tonumber(clock[2])/1000) - 946684800000
            if now < 0 or now >= scale then return redis.error_reply('country index time outside range') end
            local function valid(raw)
              local score = tonumber(raw)
              if not score or score < 0 or score >= 676*scale or score ~= math.floor(score) then
                error('corrupt country index score')
              end
              return score
            end
            local pos, result, seen, writes = 1, {}, {}, {}
            while pos <= #ARGV do
              local kind, limit, count = ARGV[pos], tonumber(ARGV[pos+1]), tonumber(ARGV[pos+2])
              pos = pos+3
              local values = {}
              for i=1,count do values[i]=ARGV[pos]; pos=pos+1 end
              local selected = {}
              local function accept(id, raw)
                local score = valid(raw)
                if not seen[id] and #selected < limit then
                  seen[id]=true
                  selected[#selected+1]=id
                  writes[#writes+1]=string.format('%.0f', math.floor(score/scale)*scale+now)
                  writes[#writes+1]=id
                end
              end
              if kind == 'any' then
                local rows = redis.call('ZRANDMEMBER', KEYS[1], limit, 'WITHSCORES')
                for i=1,#rows,2 do accept(rows[i],rows[i+1]) end
              elseif kind == 'ids' then
                local scores = redis.call('ZMSCORE', KEYS[1], unpack(values))
                local rows = {}
                for i,raw in ipairs(scores) do
                  if raw then rows[#rows+1]={values[i],valid(raw)} end
                end
                table.sort(rows,function(a,b)
                  local at,bt=a[2]%scale,b[2]%scale
                  return at < bt or (at == bt and a[1] < b[1])
                end)
                for _,row in ipairs(rows) do accept(row[1],row[2]) end
              else
                -- Merge bounded country heads by their low take time, not country prefix.
                local heads, offsets = {}, {}
                local function advance(i)
                  local lower=tonumber(values[i])*scale
                  local row=redis.call('ZRANGEBYSCORE', KEYS[1],string.format('%.0f',lower),
                    '('..string.format('%.0f',lower+scale),'WITHSCORES','LIMIT',offsets[i],1)
                  offsets[i]=offsets[i]+1
                  heads[i]=#row > 0 and {row[1],valid(row[2])} or false
                end
                for i=1,count do offsets[i]=0; advance(i) end
                while #selected < limit do
                  local best=nil
                  for i=1,count do
                    if heads[i] then
                      if not best or heads[i][2]%scale < heads[best][2]%scale or
                        (heads[i][2]%scale == heads[best][2]%scale and heads[i][1] < heads[best][1]) then best=i end
                    end
                  end
                  if not best then break end
                  accept(heads[best][1],heads[best][2])
                  advance(best)
                end
              end
              result[#result+1]=selected
            end
            if #writes > 0 then redis.call('ZADD',KEYS[1],unpack(writes)) end
            return result
            """;

    private static final String RETAIN = """
            local scale, pos, result = 8796093022208, 1, {}
            while pos <= #ARGV do
              local kind,count=ARGV[pos],tonumber(ARGV[pos+1]); pos=pos+2
              local values={}
              for i=1,count do values[ARGV[pos]]=true; pos=pos+1 end
              local size=tonumber(ARGV[pos]); pos=pos+1
              local ids={}
              for i=1,size do ids[i]=ARGV[pos]; pos=pos+1 end
              local scores=redis.call('ZMSCORE',KEYS[1],unpack(ids))
              local kept={}
              for i,raw in ipairs(scores) do
                if raw then
                  local score=tonumber(raw)
                  if not score or score < 0 or score >= 676*scale or score ~= math.floor(score) then
                    return redis.error_reply('corrupt country index score')
                  end
                  if kind == 'any' or (kind == 'ids' and values[ids[i]]) or
                    (kind == 'countries' and values[tostring(math.floor(score/scale))]) then kept[#kept+1]=ids[i] end
                end
              end
              result[#result+1]=kept
            end
            return result
            """;

    private static final tools.jackson.databind.ObjectMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    static List<Long> replaceFacts(RedisCommands<String, String> redis, String factsKey, String indexKey,
            Map<String, String> encoded) {
        var args = new ArrayList<String>();
        encoded.forEach((id, json) -> {
            args.add(id);
            args.add(json);
            // Derive membership from the exact stored snapshot, never from a caller's mutable Map.
            args.add(Integer.toString(CountryIndex.optionalCode(JSON.readValue(json, Map.class).get("country"))));
        });
        return redis.eval(STORE_INDEXED_FACTS_SCRIPT, ScriptOutputType.MULTI,
                new String[]{factsKey, indexKey}, args.toArray(String[]::new));
    }

    static void validate(TaskItemWorkerSelector selector) {
        criteria(selector);
    }

    static TaskQuery query(Supplier<RedisCommands<String, String>> commands, String key) {
        return new TaskQuery() {
            @Override
            public Map<TaskItemWorkerSelector, List<String>> take(Map<TaskItemWorkerSelector, Integer> limits) {
                Objects.requireNonNull(limits, "limits");
                if (limits.isEmpty()) return Map.of();
                if (limits.size() > 100) throw new IllegalArgumentException("at most 100 queries");
                var args = new ArrayList<String>();
                var selectors = new ArrayList<TaskItemWorkerSelector>();
                int total = 0;
                for (var entry : limits.entrySet()) {
                    Integer limit = entry.getValue();
                    if (limit == null || limit < 1 || limit > 100 || (total += limit) > 100) {
                        throw new IllegalArgumentException("total candidate limit must be in 1..100");
                    }
                    Criteria c = criteria(entry.getKey());
                    selectors.add(entry.getKey());
                    args.add(c.kind()); args.add(limit.toString()); args.add(Integer.toString(c.values().size()));
                    args.addAll(c.values());
                }
                List<?> rows = commands.get().eval(TAKE, ScriptOutputType.MULTI, new String[]{key}, args.toArray(String[]::new));
                var result = new LinkedHashMap<TaskItemWorkerSelector, List<String>>();
                for (int i=0; i<selectors.size(); i++) result.put(selectors.get(i), strings(rows.get(i)));
                return Collections.unmodifiableMap(result);
            }

            @Override
            public Map<TaskItemWorkerSelector, Set<String>> retain(Map<TaskItemWorkerSelector, List<String>> held) {
                Objects.requireNonNull(held, "held");
                if (held.size() > 100) throw new IllegalArgumentException("at most 100 queries");
                var args = new ArrayList<String>();
                var selectors = new ArrayList<TaskItemWorkerSelector>();
                var result = new LinkedHashMap<TaskItemWorkerSelector, Set<String>>();
                int total = 0;
                for (var entry : held.entrySet()) {
                    Criteria c = criteria(entry.getKey());
                    List<String> ids = List.copyOf(entry.getValue());
                    if ((total += ids.size()) > 100 || new LinkedHashSet<>(ids).size() != ids.size()
                            || ids.stream().anyMatch(String::isBlank)) {
                        throw new IllegalArgumentException("recheck requires at most 100 unique held identities");
                    }
                    result.put(entry.getKey(), Set.of());
                    if (ids.isEmpty()) continue;
                    selectors.add(entry.getKey());
                    args.add(c.kind()); args.add(Integer.toString(c.values().size())); args.addAll(c.values());
                    args.add(Integer.toString(ids.size())); args.addAll(ids);
                }
                if (!args.isEmpty()) {
                    List<?> rows = commands.get().eval(RETAIN, ScriptOutputType.MULTI, new String[]{key}, args.toArray(String[]::new));
                    for (int i=0; i<selectors.size(); i++) result.put(selectors.get(i), Set.copyOf(strings(rows.get(i))));
                }
                return Collections.unmodifiableMap(result);
            }
        };
    }

    private record Criteria(String kind, List<String> values) { }

    private static Criteria criteria(TaskItemWorkerSelector selector) {
        Objects.requireNonNull(selector, "workerSelector");
        if (selector.isAny()) return new Criteria("any", List.of());
        if (selector.hasExplicitWorkerIds()) return new Criteria("ids", selector.targetWorkerIds());
        Object expression = selector.expression().get(ID);
        if (!(expression instanceof Map<?, ?> condition) || !condition.keySet().equals(Set.of("op", "values"))
                || !(condition.get("op") instanceof String op) || !(condition.get("values") instanceof List<?> values)
                || values.isEmpty() || values.size() > 100
                || !(op.equals("in") || op.equals("eq") && values.size() == 1)) {
            throw new IllegalArgumentException("country query requires eq with one value or in with 1..100 values");
        }
        var codes = new LinkedHashSet<String>();
        for (Object value : values) {
            if (!(value instanceof String country)) throw new IllegalArgumentException("country must be a string");
            codes.add(Integer.toString(CountryIndex.code(country)));
        }
        return new Criteria("countries", List.copyOf(codes));
    }

    private static List<String> strings(Object row) {
        return ((List<?>) row).stream().map(String.class::cast).toList();
    }
}
