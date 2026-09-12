package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.WorkerCandidateIndex.TaskQuery;
import com.xa.mass.kernel.task.TaskItemWorkerSelector;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.*;
import java.util.function.Supplier;

/** Bounded query mechanics shared by explicitly composed Rules; coordinates never leave Matching. */
final class RuleIndex {
    private RuleIndex() { }
    record Criteria(String partition, String kind, List<String> values) { }
    // Both facts replacement and take-time rotation validate metadata before their first write.
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
    private static final String TAKE = PARTITIONS_LUA + """
            local scale = 8796093022208
            local clock = redis.call('TIME')
            local now = tonumber(clock[1])*1000 + math.floor(tonumber(clock[2])/1000) - 946684800000
            if now < 0 or now >= scale then return redis.error_reply('Rule index time outside range') end
            local function valid(raw)
              local score = tonumber(raw)
              if not score or score < 0 or score >= 676*scale or score ~= math.floor(score) then
                error('corrupt Rule index score')
              end
              return score
            end
            local pos, result, seen, writes = 1, {}, {}, {}
            while pos <= #ARGV do
              local suffix, kind, limit, count = ARGV[pos], ARGV[pos+1], tonumber(ARGV[pos+2]), tonumber(ARGV[pos+3])
              local key = suffix == '' and KEYS[1] or KEYS[1]..':partition:'..redis.sha1hex(suffix)
              pos = pos+4
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
                local rows = redis.call('ZRANDMEMBER', key, limit, 'WITHSCORES')
                for i=1,#rows,2 do accept(rows[i],rows[i+1]) end
              elseif kind == 'ids' then
                local scores = redis.call('ZMSCORE', key, unpack(values))
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
                  local row=redis.call('ZRANGEBYSCORE', key,string.format('%.0f',lower),
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
            local memberships={}
            for i=1,#writes,2 do
              local parts=partitions(KEYS[1],writes[i+1])
              checkPartitionKeys(KEYS[1],parts)
              memberships[i]=parts
            end
            if #writes > 0 then
              redis.call('ZADD',KEYS[1],unpack(writes))
              for i=1,#writes,2 do
                for _,suffix in ipairs(memberships[i]) do
                  redis.call('ZADD',KEYS[1]..':partition:'..redis.sha1hex(suffix),writes[i],writes[i+1])
                end
              end
            end
            return result
            """;

    private static final String RETAIN = """
            local scale, pos, result = 8796093022208, 1, {}
            while pos <= #ARGV do
              local suffix,kind,count=ARGV[pos],ARGV[pos+1],tonumber(ARGV[pos+2]); pos=pos+3
              local key=suffix == '' and KEYS[1] or KEYS[1]..':partition:'..redis.sha1hex(suffix)
              local values={}
              for i=1,count do values[ARGV[pos]]=true; pos=pos+1 end
              local size=tonumber(ARGV[pos]); pos=pos+1
              local ids={}
              for i=1,size do ids[i]=ARGV[pos]; pos=pos+1 end
              local scores=redis.call('ZMSCORE',key,unpack(ids))
              local kept={}
              for i,raw in ipairs(scores) do
                if raw then
                  local score=tonumber(raw)
                  if not score or score < 0 or score >= 676*scale or score ~= math.floor(score) then
                    return redis.error_reply('corrupt Rule index score')
                  end
                  if kind == 'any' or (kind == 'ids' and values[ids[i]]) or
                    (kind == 'countries' and values[tostring(math.floor(score/scale))]) then kept[#kept+1]=ids[i] end
                end
              end
              result[#result+1]=kept
            end
            return result
            """;

    static TaskQuery query(Supplier<RedisCommands<String, String>> commands, String key, java.util.function.Function<TaskItemWorkerSelector, Criteria> interpret) {
        return new TaskQuery() {
            @Override public boolean usesIdentitySelection(TaskItemWorkerSelector selector) { return false; }
            @Override public void validate(TaskItemWorkerSelector selector) { interpret.apply(selector); }
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
                    Criteria c = interpret.apply(entry.getKey());
                    selectors.add(entry.getKey());
                    args.add(c.partition()); args.add(c.kind()); args.add(limit.toString()); args.add(Integer.toString(c.values().size()));
                    args.addAll(c.values());
                }
                List<?> rows = commands.get().eval(TAKE, ScriptOutputType.MULTI, new String[]{key, key+":partitions"}, args.toArray(String[]::new));
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
                    Criteria c = interpret.apply(entry.getKey());
                    List<String> ids = List.copyOf(entry.getValue());
                    if ((total += ids.size()) > 100 || new LinkedHashSet<>(ids).size() != ids.size()
                            || ids.stream().anyMatch(String::isBlank)) {
                        throw new IllegalArgumentException("recheck requires at most 100 unique held identities");
                    }
                    result.put(entry.getKey(), Set.of());
                    if (ids.isEmpty()) continue;
                    selectors.add(entry.getKey());
                    args.add(c.partition()); args.add(c.kind()); args.add(Integer.toString(c.values().size())); args.addAll(c.values());
                    args.add(Integer.toString(ids.size())); args.addAll(ids);
                }
                if (!args.isEmpty()) {
                    List<?> rows = commands.get().eval(RETAIN, ScriptOutputType.MULTI, new String[]{key, key+":partitions"}, args.toArray(String[]::new));
                    for (int i=0; i<selectors.size(); i++) result.put(selectors.get(i), Set.copyOf(strings(rows.get(i))));
                }
                return Collections.unmodifiableMap(result);
            }
        };
    }

    private static List<String> strings(Object row) {
        return ((List<?>) row).stream().map(String.class::cast).toList();
    }
}
