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
