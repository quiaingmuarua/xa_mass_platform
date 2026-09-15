package com.xa.mass.workermatching.rules;

import com.xa.mass.kernel.redis.RedisKeyspace;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongSupplier;

/** Implementation-side Redis and candidate resources. Created once per Catalog, never per Task. */
public final class RedisRuleStorage implements AutoCloseable {
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
    private final RedisClient client;
    private final RedisKeyspace keyspace;
    private final Map<String, List<IndexMutation>> indexes;
    private final LongSupplier clock;
    private final List<LocalCandidateRule<?>> candidateOwners = new ArrayList<>();
    final CandidateBudget budget = new CandidateBudget();
    private StatefulRedisConnection<String, String> connection;
    private boolean closed;
    public RedisRuleStorage(RedisClient client, RedisKeyspace keyspace) {
        this(client, keyspace, Map.of(), System::currentTimeMillis);
    }
    public RedisRuleStorage(RedisClient client, RedisKeyspace keyspace,
            Map<String, List<IndexMutation>> additionalIndexes, LongSupplier clock) {
        this.client = Objects.requireNonNull(client); this.keyspace = Objects.requireNonNull(keyspace);
        this.clock = Objects.requireNonNull(clock);
        var mutations = new LinkedHashMap<String, List<IndexMutation>>();
        mutations.put("worker.country", List.of(CountryRuleHandler.index()));
        mutations.put("worker.messaging.available", List.of(MessagingRuleHandler.index()));
        mutations.put("proof.worker.facts", List.of(ProofFactsRuleHandler.index()));
        additionalIndexes.forEach((id, values) -> {
            if (mutations.putIfAbsent(id, List.copyOf(values)) != null)
                throw new IllegalArgumentException("duplicate Rule storage: " + id);
        });
        var namespaces = new HashSet<String>();
        mutations.values().forEach(values -> values.forEach(index -> {
            if (!namespaces.add(index.namespace())) throw new IllegalArgumentException("Conflicting Rule index namespace: " + index.namespace());
        }));
        indexes = Map.copyOf(mutations);
    }
    public List<IndexMutation> indexes(String ruleId) { return indexes.getOrDefault(ruleId, List.of()); }
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
    synchronized void addCandidateOwner(LocalCandidateRule<?> owner) { candidateOwners.add(owner); }
    public void expireCandidates() {
        List<LocalCandidateRule<?>> owners;
        synchronized (this) { owners = List.copyOf(candidateOwners); }
        owners.forEach(LocalCandidateRule::expireAll);
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (connection != null) connection.close();
    }
}
