package com.xa.mass.workermatching.rules;

import com.xa.mass.kernel.redis.RedisKeyspace;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.nio.charset.StandardCharsets;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.function.LongSupplier;

/** Implementation-side Redis and candidate resources. Created once per Catalog, never per Task. */
public final class MatchingStorage implements AutoCloseable {
    /**
     * Trusted storage assembly input, separate from Eligibility operations. Lua returns prepare;
     * prepare validates without writes and returns apply. Facts and every enabled index prepare
     * in one script before its first write. Never accept this program from an external request.
     */
    public record IndexMutation(String namespace, String prepareLua) {
        public IndexMutation {
            if (namespace == null || !namespace.matches("[A-Za-z0-9_-]+") || prepareLua == null || prepareLua.isBlank())
                throw new IllegalArgumentException("invalid Rule index mutation");
        }
    }
    private static final ObjectMapper FACTS_JSON = JsonMapper.builder().enable(DeserializationFeature.USE_LONG_FOR_INTS).build();
    public static Map<String, Object> decodeObject(String raw) {
        try {
            Map<String, Object> value = FACTS_JSON.readValue(raw, new TypeReference<Map<String, Object>>() { });
            if (value == null) throw new IllegalArgumentException("value must be an object");
            return Collections.unmodifiableMap(new LinkedHashMap<>(value));
        } catch (JacksonException error) { throw new IllegalArgumentException("stored JSON is malformed", error); }
    }
    public String workerFactsKey(String group) { return base() + ":matching:worker:facts:" + group; }
    /** Internal supply read: one bounded Worker HASH read, no Platform facts or index scan. */
    Map<String, Map<String, Object>> readWorkerFacts(String group, List<String> ids) {
        if (ids.size() > 100 || new HashSet<>(ids).size() != ids.size())
            throw new IllegalArgumentException("at most 100 unique Worker identities");
        if (ids.isEmpty()) return Map.of();
        var result = new LinkedHashMap<String, Map<String, Object>>();
        for (var value : commands().hmget(workerFactsKey(group), ids.toArray(String[]::new)))
            if (value.hasValue()) result.put(value.getKey(), decodeObject(value.getValue()));
        return Collections.unmodifiableMap(result);
    }
    private final RedisClient client;
    private final RedisKeyspace keyspace;
    private final LongSupplier clock;
    private final List<CandidatePool> candidateOwners = new ArrayList<>();
    final CandidateBudget budget = new CandidateBudget();
    private StatefulRedisConnection<String, String> connection;
    private boolean closed;
    public MatchingStorage(RedisClient client, RedisKeyspace keyspace) {
        this(client, keyspace, System::currentTimeMillis);
    }
    public MatchingStorage(RedisClient client, RedisKeyspace keyspace, LongSupplier clock) {
        this.client = Objects.requireNonNull(client);
        this.keyspace = Objects.requireNonNull(keyspace);
        this.clock = Objects.requireNonNull(clock);
    }
    public long now() { return clock.getAsLong(); }
    public int availableCapacity() { return budget.available(); }
    public String diagnostics() { return budget.diagnostics(); }
    public String base() { return keyspace.base(); }
    public String indexBase(String group) {
        return base() + ":matching:worker:index:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(group.getBytes(StandardCharsets.UTF_8));
    }
    public String indexKey(String group, String namespace) { return indexBase(group) + ":" + namespace; }
    public synchronized RedisCommands<String, String> commands() {
        if (closed) throw new IllegalStateException("Matching storage is closed");
        if (connection == null) connection = client.connect(StringCodec.UTF8);
        return connection.sync();
    }
    // Fixed construction-time ownership, not a public registration or lifecycle SPI.
    synchronized void addCandidateOwner(CandidatePool owner) { candidateOwners.add(owner); }
    public void expireCandidates() {
        List<CandidatePool> owners;
        synchronized (this) { owners = List.copyOf(candidateOwners); }
        owners.forEach(CandidatePool::expireAll);
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (connection != null) connection.close();
    }
}
