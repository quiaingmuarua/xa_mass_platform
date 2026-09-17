package com.xa.mass.workermatching.storage;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.workermatching.WorkerMatchingCatalog.WorkerFacts;
import com.xa.mass.workermatching.index.IndexMutation;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.KeyValue;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.*;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Facts, enabled index commits and their shared Redis connection. No candidate inventory. */
public final class FactsIndexStore implements AutoCloseable {
    private final RedisClient client;
    private final RedisKeyspace keyspace;
    private final Map<String, List<IndexMutation>> indexesByGroup;
    private final Map<String, String> scriptsByGroup;
    private StatefulRedisConnection<String, String> connection;
    private boolean closed;

    public FactsIndexStore(RedisClient client, RedisKeyspace keyspace,
            Map<String, List<IndexMutation>> indexesByGroup) {
        this.client = Objects.requireNonNull(client);
        this.keyspace = Objects.requireNonNull(keyspace);
        var indexes = new LinkedHashMap<String, List<IndexMutation>>();
        var scripts = new LinkedHashMap<String, String>();
        indexesByGroup.forEach((group, mutations) -> {
            var unique = new LinkedHashMap<String, IndexMutation>();
            for (var mutation : mutations) {
                var prior = unique.putIfAbsent(mutation.namespace(), mutation);
                if (prior != null && !prior.equals(mutation))
                    throw new IllegalArgumentException("Conflicting index resource");
            }
            var captured = List.copyOf(unique.values());
            indexes.put(group, captured); scripts.put(group, script(captured));
        });
        this.indexesByGroup = Map.copyOf(indexes);
        this.scriptsByGroup = Map.copyOf(scripts);
    }

    public RedisKeyspace keyspace() { return keyspace; }
    public Set<String> indexedGroups() { return indexesByGroup.keySet(); }

    public synchronized RedisCommands<String, String> commands() {
        if (closed) throw new IllegalStateException("Matching storage is closed");
        if (connection == null) connection = client.connect(StringCodec.UTF8);
        return connection.sync();
    }

    public List<Long> replaceWorkerFacts(String group, List<String> encodedPairs) {
        return mutate(group, "replace", encodedPairs);
    }

    public long patchPlatformProperties(String group, String workerId, String encoded) {
        return mutate(group, "patch", List.of(workerId, encoded)).getFirst();
    }

    private List<Long> mutate(String group, String mode, List<String> input) {
        var args = new ArrayList<String>(); args.add(mode); args.addAll(input);
        return commands().eval(scriptsByGroup.getOrDefault(group, NO_INDEX_SCRIPT), ScriptOutputType.MULTI,
                new String[]{workerFactsKey(group), workerPlatformFactsKey(group), IndexMutation.base(keyspace, group)},
                args.toArray(String[]::new));
    }

    private String workerPlatformFactsKey(String group) {
        return keyspace.base() + ":matching:worker:platform-properties:" + group;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (connection != null) connection.close();
    }

    private static final ObjectMapper FACTS_JSON = JsonMapper.builder().enable(DeserializationFeature.USE_LONG_FOR_INTS).build();
    public static Map<String, Object> decodeObject(String raw) {
        try {
            Map<String, Object> value = FACTS_JSON.readValue(raw, new TypeReference<Map<String, Object>>() { });
            if (value == null) throw new IllegalArgumentException("value must be an object");
            return Collections.unmodifiableMap(new LinkedHashMap<>(value));
        } catch (JacksonException error) { throw new IllegalArgumentException("stored JSON is malformed", error); }
    }
    public String workerFactsKey(String group) { return keyspace.base() + ":matching:worker:facts:" + group; }
    /** Internal supply read: one bounded Worker HASH read, no Platform facts or index scan. */
    public Map<String, Map<String, Object>> readWorkerFacts(String group, List<String> ids) {
        if (ids.size() > 100 || new HashSet<>(ids).size() != ids.size())
            throw new IllegalArgumentException("at most 100 unique Worker identities");
        if (ids.isEmpty()) return Map.of();
        var result = new LinkedHashMap<String, Map<String, Object>>();
        for (var value : commands().hmget(workerFactsKey(group), ids.toArray(String[]::new)))
            if (value.hasValue()) result.put(value.getKey(), decodeObject(value.getValue()));
        return Collections.unmodifiableMap(result);
    }
    /** Startup only, before facts admission and Pacer start. Never scheduled in the background. */
    public void rebuildIndexes() {
        for (String group:indexesByGroup.keySet()) {
            if(indexesByGroup.get(group).isEmpty())continue;
            var redis=commands();
            ScanCursor cursor;
            for(var index:indexesByGroup.get(group)) {
                String root=IndexMutation.base(keyspace, group)+":"+index.namespace();
                // The exact root and its descendants only; never another resource's index.
                redis.unlink(root);
                cursor=ScanCursor.INITIAL;
                do {
                    var page=redis.scan(cursor,new ScanArgs().match(root+":*").limit(100));
                    if(!page.getKeys().isEmpty())redis.unlink(page.getKeys().toArray(String[]::new));
                    cursor=page;
                } while(!cursor.isFinished());
            }
            cursor=ScanCursor.INITIAL;
            do {
                var page=redis.hscan(workerFactsKey(group),cursor,new ScanArgs().limit(100));
                var ids=new ArrayList<String>();
                for (var entry:page.getMap().entrySet()) {
                    decodeObject(entry.getValue()); ids.add(entry.getKey()); ids.add("{}");
                    if (ids.size()==200) { mutate(group,"rebuild",ids); ids.clear(); }
                }
                if (!ids.isEmpty()) mutate(group,"rebuild",ids);
                cursor=page;
            } while (!cursor.isFinished());
        }
    }

    public Map<String, @Nullable WorkerFacts> loadWorkerFacts(
            String workerGroupId,
            List<String> workerIds
    ) {
        List<String> ids = workerIds;
        if (ids.isEmpty()) {
            return Map.of();
        }
        RedisCommands<String, String> commands = commands();
        List<KeyValue<String, String>> workers = commands.hmget(
                workerFactsKey(workerGroupId),
                ids.toArray(String[]::new)
        );
        List<KeyValue<String, String>> platforms = commands.hmget(
                workerPlatformFactsKey(workerGroupId),
                ids.toArray(String[]::new)
        );
        LinkedHashMap<String, WorkerFacts> result = new LinkedHashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            String workerId = ids.get(index);
            String workerRaw = workers.get(index).getValueOrElse(null);
            if (workerRaw == null) {
                result.put(workerId, null);
                continue;
            }
            try {
                String platformRaw = platforms.get(index).getValueOrElse(null);
                result.put(workerId, new WorkerFacts(
                        workerId,
                        workerGroupId,
                        decodeObject(workerRaw),
                        platformRaw == null
                                ? Map.of()
                                : decodeObject(platformRaw)
                ));
            } catch (IllegalArgumentException error) {
                result.put(workerId, null);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    public static String encodeObject(Map<String, ?> value) {
        try {
            return FACTS_JSON.writeValueAsString(canonicalJsonValue(value));
        } catch (JacksonException error) {
            throw new IllegalArgumentException(
                    "value is not JSON-compatible",
                    error
            );
        }
    }

    private static Object canonicalJsonValue(Object value) {
        if (value instanceof Map<?, ?> mapping) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            mapping.forEach((key, item) -> {
                if (!(key instanceof String stringKey)) {
                    throw new IllegalArgumentException(
                            "JSON object keys must be strings"
                    );
                }
                sorted.put(stringKey, canonicalJsonValue(item));
            });
            return sorted;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> items = new ArrayList<>(collection.size());
            collection.forEach(item -> items.add(canonicalJsonValue(item)));
            return items;
        }
        return snapshotJsonValue(value);
    }

    private static Object snapshotJsonValue(Object value) {
        return canonicalJsonValueScalar(value);
    }

    private static Object canonicalJsonValueScalar(Object value) {
        if (value == null || value instanceof String
                || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Double number && !Double.isFinite(number)) {
            throw new IllegalArgumentException("JSON numbers must be finite");
        }
        if (value instanceof Float number && !Float.isFinite(number)) {
            throw new IllegalArgumentException("JSON numbers must be finite");
        }
        if (value instanceof Number) {
            return value;
        }
        if (value instanceof Map<?, ?> || value instanceof Collection<?>) {
            return canonicalJsonValue(value);
        }
        throw new IllegalArgumentException("value is not JSON-compatible");
    }

    private static final String HELPERS = """
            local function object(raw)
              if not raw or not string.match(raw,'^%s*{') then error('corrupt Matching facts') end
              local value=cjson.decode(raw)
              if type(value)~='table' then error('corrupt Matching facts') end
              return value
            end
            -- Preserve nested JSON shapes, including empty arrays, while merging only top-level fields.
            local function fields(raw)
              object(raw)
              local result, i = {}, string.find(raw,'{',1,true)+1
              while i <= #raw do
                while string.match(string.sub(raw,i,i),'[%s,]') do i=i+1 end
                if string.sub(raw,i,i)=='}' then break end
                local start=i; i=i+1
                while i<=#raw do
                  local c=string.sub(raw,i,i)
                  if c=='\\\\' then i=i+2 elseif c=='"' then i=i+1; break else i=i+1 end
                end
                local name=cjson.decode(string.sub(raw,start,i-1))
                while string.match(string.sub(raw,i,i),'[%s:]') do i=i+1 end
                start=i
                local depth,quoted=0,false
                while i<=#raw do
                  local c=string.sub(raw,i,i)
                  if quoted then
                    if c=='\\\\' then i=i+1 elseif c=='"' then quoted=false end
                  elseif c=='"' then quoted=true
                  elseif c=='{' or c=='[' then depth=depth+1
                  elseif c=='}' or c==']' then if depth==0 then break end; depth=depth-1
                  elseif c==',' and depth==0 then break end
                  i=i+1
                end
                result[name]=string.match(string.sub(raw,start,i-1),'^%s*(.-)%s*$')
              end
              return result
            end
            local function patch(current,delta)
              local values=fields(current)
              for name,raw in pairs(fields(delta)) do values[name]=raw~='null' and raw or nil end
              local names={}; for name in pairs(values) do names[#names+1]=name end; table.sort(names)
              local encoded={}; for _,name in ipairs(names) do encoded[#encoded+1]=cjson.encode(name)..':'..values[name] end
              return '{'..table.concat(encoded,',')..'}'
            end
            """;

    private static String script(List<IndexMutation> indexes) {
        var source=new StringBuilder(HELPERS);
        for (int i=0;i<indexes.size();i++) source.append("local prepare_").append(i)
                .append("=(function()\n").append(indexes.get(i).prepareLua()).append("\nend)()\n");
        source.append("""
                local results, writes, updates={},{},{}
                local mode=ARGV[1]
                for i=2,#ARGV,2 do
                  local id,input=ARGV[i],ARGV[i+1]
                  local old=redis.call('HGET',KEYS[1],id)
                  local platform=redis.call('HGET',KEYS[2],id) or '{}'
                  if mode=='patch' and not old then results[#results+1]=-1
                  else
                    if old then object(old) end
                    local replacement=mode=='replace' and input or old
                    local nextPlatform=mode=='patch' and patch(platform,input) or platform
                    local w,p=object(replacement),object(nextPlatform)
                    local effect=(mode=='replace' and old~=replacement or mode=='patch' and platform~=nextPlatform) and 1 or 0
                    results[#results+1]=effect
                    if effect==1 then writes[#writes+1]={mode=='patch' and KEYS[2] or KEYS[1],id,mode=='patch' and nextPlatform or replacement} end
                """);
        for (int i=0;i<indexes.size();i++) source.append("local apply=prepare_").append(i)
                .append("(KEYS[3]..':").append(indexes.get(i).namespace()).append("',id,w,p)\nif type(apply)~='function' then error('invalid Rule update') end\nupdates[#updates+1]=apply\n");
        source.append("""
                  end
                end
                for _,w in ipairs(writes) do redis.call('HSET',unpack(w)) end
                for _,apply in ipairs(updates) do apply() end
                return results
                """);
        return source.toString();
    }
    private static final String NO_INDEX_SCRIPT = script(List.of());
}
