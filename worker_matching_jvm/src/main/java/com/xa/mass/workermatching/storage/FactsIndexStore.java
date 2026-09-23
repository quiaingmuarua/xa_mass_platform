package com.xa.mass.workermatching.storage;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.workermatching.WorkerProperties;
import com.xa.mass.workermatching.index.RedisHashPropertyIndex;
import io.lettuce.core.RedisClient;
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
public final class FactsIndexStore implements WorkerProperties, AutoCloseable {
    private final RedisClient client;
    private final RedisKeyspace keyspace;
    private final Map<String, Set<String>> indexedPropertiesByGroup;
    private StatefulRedisConnection<String, String> connection;
    private boolean closed;

    public FactsIndexStore(RedisClient client, RedisKeyspace keyspace,
            Map<String, Set<String>> indexedPropertiesByGroup) {
        this.client = Objects.requireNonNull(client);
        this.keyspace = Objects.requireNonNull(keyspace);
        var indexes = new LinkedHashMap<String, Set<String>>();
        indexedPropertiesByGroup.forEach((group, properties) -> {
            if (group == null || group.isBlank()) throw new IllegalArgumentException("Group must be nonblank");
            var captured = new LinkedHashSet<>(properties);
            captured.forEach(property -> RedisHashPropertyIndex.key(keyspace, group, property));
            indexes.put(group, Collections.unmodifiableSet(captured));
        });
        this.indexedPropertiesByGroup = Collections.unmodifiableMap(indexes);
    }

    public RedisKeyspace keyspace() { return keyspace; }
    public Set<String> indexedGroups() { return indexedPropertiesByGroup.keySet(); }

    public synchronized RedisCommands<String, String> commands() {
        if (closed) throw new IllegalStateException("Matching storage is closed");
        if (connection == null) connection = client.connect(StringCodec.UTF8);
        return connection.sync();
    }

    @Override public Map<String,MutationResult> upsertWorkerFactsBatch(String group,Map<String,Map<String,String>> facts) {
        requireNonBlank(group,"workerGroupId"); Objects.requireNonNull(facts,"facts");
        if (facts.isEmpty() || facts.size()>100) throw new IllegalArgumentException("Worker facts batch must contain 1..100 entries");
        var args=new ArrayList<String>(); var ids=new ArrayList<String>(); var result=new LinkedHashMap<String,MutationResult>();
        facts.forEach((id,properties) -> {
            requireNonBlank(id,"workerId");
            if (properties==null || ((Map<?,?>)properties).entrySet().stream().anyMatch(e -> !(e.getKey() instanceof String key) || key.isBlank() || !(e.getValue() instanceof String))) {
                result.put(id,result(MutationStatus.INVALID,"invalid Worker properties"));
            } else { ids.add(id); args.add(id); args.add(encodeObject(properties)); }
        });
        if (!args.isEmpty()) {
            var effects=replaceWorkerFacts(group,args);
            for (int i=0;i<ids.size();i++) result.put(ids.get(i),new MutationResult(effects.get(i)==0 ? MutationStatus.UNCHANGED : MutationStatus.APPLIED));
        }
        var ordered=new LinkedHashMap<String,MutationResult>(); facts.keySet().forEach(id -> ordered.put(id,result.get(id)));
        return Collections.unmodifiableMap(ordered);
    }

    @Override public MutationResult patchWorkerPlatformProperties(String group,String id,Map<String,@Nullable Object> properties) {
        requireNonBlank(group,"workerGroupId"); requireNonBlank(id,"workerId"); Objects.requireNonNull(properties,"properties");
        String encoded;
        try {
            if (properties.size()>100 || properties.keySet().stream().anyMatch(key -> key==null || key.isBlank())) throw new IllegalArgumentException("invalid property names");
            encoded=encodeObject(properties);
        } catch (IllegalArgumentException invalid) { return result(MutationStatus.INVALID,"invalid platform properties"); }
        long effect=patchPlatformProperties(group,id,encoded);
        return new MutationResult(effect<0 ? MutationStatus.NOT_FOUND : effect==0 ? MutationStatus.UNCHANGED : MutationStatus.APPLIED);
    }

    private static MutationResult result(
            MutationStatus status,
            String reason
    ) {
        return new MutationResult(status, reason);
    }

    private static List<String> boundedUnique(
            List<String> values,
            String name
    ) {
        Objects.requireNonNull(values, name);
        if (values.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    name + " must contain at most " + MAX_BATCH_SIZE + " entries"
            );
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            requireNonBlank(value, name + " entry");
            if (!unique.add(value)) {
                throw new IllegalArgumentException(
                        name + " must not contain duplicates"
                );
            }
        }
        return List.copyOf(unique);
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }

    private List<Long> replaceWorkerFacts(String group, List<String> encodedPairs) {
        if (encodedPairs.size() > 200 || encodedPairs.size() % 2 != 0)
            throw new IllegalArgumentException("Worker facts write requires at most 100 identity/value pairs");
        if (encodedPairs.isEmpty()) return List.of();
        var keys = new ArrayList<String>();
        keys.add(workerFactsKey(group)); keys.add(workerPlatformFactsKey(group));
        var args = new ArrayList<String>();
        for (String property : indexedPropertiesByGroup.getOrDefault(group, Set.of())) {
            keys.add(RedisHashPropertyIndex.key(keyspace, group, property));
            args.add(property);
        }
        args.addAll(encodedPairs);
        return commands().eval(REPLACE_WORKER_FACTS, ScriptOutputType.MULTI,
                keys.toArray(String[]::new), args.toArray(String[]::new));
    }

    private long patchPlatformProperties(String group, String workerId, String encoded) {
        return commands().eval(PATCH_PLATFORM_PROPERTIES, ScriptOutputType.INTEGER,
                new String[]{workerFactsKey(group), workerPlatformFactsKey(group)}, workerId, encoded);
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
        if (new HashSet<>(ids).size() != ids.size())
            throw new IllegalArgumentException("unique Worker identities required");
        if (ids.isEmpty()) return Map.of();
        var result = new LinkedHashMap<String, Map<String, Object>>();
        for (var value : commands().hmget(workerFactsKey(group), ids.toArray(String[]::new)))
            if (value.hasValue()) result.put(value.getKey(), decodeObject(value.getValue()));
        return Collections.unmodifiableMap(result);
    }
    private static final String FACTS_SNAPSHOT = """
            return {redis.call('HMGET',KEYS[1],unpack(ARGV)), redis.call('HMGET',KEYS[2],unpack(ARGV))}
            """;

    /** Internal qualification snapshot. Both HASH reads share one Redis execution; corrupt present facts fail the batch. */
    public Map<String, WorkerFacts> readFactsSnapshot(String group, List<String> ids) {
        if (new HashSet<>(ids).size() != ids.size())
            throw new IllegalArgumentException("unique Worker identities required");
        if (ids.isEmpty()) return Map.of();
        List<List<String>> rows = commands().evalReadOnly(FACTS_SNAPSHOT, ScriptOutputType.MULTI,
                new String[]{workerFactsKey(group), workerPlatformFactsKey(group)}, ids.toArray(String[]::new));
        var result = new LinkedHashMap<String, WorkerFacts>();
        for (int i = 0; i < ids.size(); i++) {
            String worker = rows.get(0).get(i), platform = rows.get(1).get(i);
            if (worker != null) result.put(ids.get(i), new WorkerFacts(ids.get(i), group,
                    decodeObject(worker), platform == null ? Map.of() : decodeObject(platform)));
        }
        return Collections.unmodifiableMap(result);
    }

    @Override public Map<String, @Nullable WorkerFacts> loadWorkerFacts(
            String workerGroupId,
            List<String> workerIds
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        List<String> ids = boundedUnique(workerIds, "workerIds");
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

    private static String encodeObject(Map<String, ?> value) {
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

    private static final String OBJECT = """
            local function object(raw)
              if not raw or not string.match(raw,'^%s*{') then error('corrupt Matching facts') end
              local value=cjson.decode(raw)
              if type(value)~='table' then error('corrupt Matching facts') end
              return value
            end
            """;
    private static final String PATCH_HELPERS = OBJECT + """
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

    private static final String REPLACE_WORKER_FACTS = OBJECT + """
            local count=#KEYS-2
            for _,key in ipairs(KEYS) do
              local kind=redis.call('TYPE',key).ok
              if kind~='none' and kind~='hash' then error('corrupt Matching HASH type') end
            end
            local prepared,results={},{}
            for i=count+1,#ARGV,2 do
              local id,input=ARGV[i],ARGV[i+1]
              local old=redis.call('HGET',KEYS[1],id)
              local before=old and object(old) or {}
              local after=object(input)
              object(redis.call('HGET',KEYS[2],id) or '{}')
              prepared[#prepared+1]={id=id,input=input,before=before,after=after,changed=old~=input}
              results[#results+1]=old~=input and 1 or 0
            end
            local function value(properties,name)
              local v=properties[name]
              return type(v)=='string' and v~='' and v or nil
            end
            for _,row in ipairs(prepared) do
              for j=1,count do
                local key=KEYS[j+2]
                local old,next=value(row.before,ARGV[j]),value(row.after,ARGV[j])
                if old and old~=next and redis.call('HGET',key,old)==row.id then
                  redis.call('HDEL',key,old)
                end
                if next then redis.call('HSET',key,next,row.id) end
              end
              if row.changed then redis.call('HSET',KEYS[1],row.id,row.input) end
            end
            return results
            """;

    private static final String PATCH_PLATFORM_PROPERTIES = PATCH_HELPERS + """
            local old=redis.call('HGET',KEYS[1],ARGV[1])
            local platform=redis.call('HGET',KEYS[2],ARGV[1]) or '{}'
            if not old then return -1 end
            object(old)
            local next=patch(platform,ARGV[2])
            if platform==next then return 0 end
            redis.call('HSET',KEYS[2],ARGV[1],next)
            return 1
            """;
}
