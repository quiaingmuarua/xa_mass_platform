package com.xa.mass.server.worker.connection;

import tools.jackson.databind.ObjectMapper;
import com.xa.mass.kernel.redis.RedisKeyspace;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Server-owned current admission decision. No connection history or expiry worker. */
public final class WorkerConnectionControlStore implements AutoCloseable {
    private static final String REPLACE = """
            local current = redis.call('HGET', KEYS[1], ARGV[1])
            if (current or '') ~= ARGV[2] then return 0 end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])
            return 1
            """;
    private final RedisClient client;
    private final RedisKeyspace keyspace;
    private final ObjectMapper json;
    private volatile StatefulRedisConnection<String, String> connection;
    private boolean closed;

    public WorkerConnectionControlStore(RedisClient client, RedisKeyspace keyspace, ObjectMapper json) {
        this.client = Objects.requireNonNull(client);
        this.keyspace = Objects.requireNonNull(keyspace);
        this.json = Objects.requireNonNull(json);
    }

    public CompletableFuture<Map<String, Control>> readAllAsync(String groupId, List<String> workerIds) {
        if (workerIds.isEmpty() || workerIds.size() > 100 || workerIds.stream().anyMatch(id -> id == null || id.isBlank()))
            throw new IllegalArgumentException("Read requires 1..100 known Worker IDs");
        return connection().async().hmget(key(groupId), workerIds.toArray(String[]::new)).toCompletableFuture()
                .thenApply(values -> {
                    Map<String, Control> result = new LinkedHashMap<>();
                    values.forEach(value -> { if (value.hasValue()) result.put(value.getKey(), decode(value.getValue())); });
                    return Map.copyOf(result);
                });
    }

    public Control read(String groupId, String workerId) {
        String raw = connection().sync().hget(key(groupId), workerId);
        return raw == null ? null : decode(raw);
    }

    /** Exact last-request retry is checked before time validity, so an expired request remains reconcilable. */
    public Control update(String groupId, String workerId, Change change, String actorId, long now) {
        String raw = connection().sync().hget(key(groupId), workerId);
        Control current = raw == null ? null : decode(raw);
        if (current != null && current.requestId().equals(change.requestId())) {
            if (!current.matches(change, actorId)) throw new Conflict();
            return current;
        }
        if (!Objects.equals(current == null ? null : current.revision(), change.expectedRevision())) throw new Conflict();
        if (change.cooldownUntilMillis() != null && change.cooldownUntilMillis() <= now)
            throw new IllegalArgumentException("New cooldown deadline must be in the future");
        Control next = new Control(UUID.randomUUID().toString(), change.requestId(), change.expectedRevision(),
                change.cooldownUntilMillis(), change.reason(), actorId, now);
        Long replaced = connection().sync().eval(REPLACE, ScriptOutputType.INTEGER,
                new String[]{key(groupId)}, workerId, raw == null ? "" : raw, encode(next));
        if (!Long.valueOf(1).equals(replaced)) throw new Conflict();
        return next;
    }

    private String key(String groupId) {
        if (groupId == null || groupId.isBlank()) throw new IllegalArgumentException("Group is required");
        return keyspace.base() + ":server:worker-connection-control:" + groupId;
    }

    private Control decode(String raw) {
        try {
            var node = json.readTree(raw);
            if (!node.isObject() || !node.propertyNames().equals(Set.of("revision", "requestId", "expectedRevision",
                    "cooldownUntilMillis", "reason", "actorId", "updatedAt")))
                throw new IllegalArgumentException("Stored control fields are incomplete");
            for (String field : List.of("revision", "requestId", "reason", "actorId"))
                if (!node.get(field).isTextual()) throw new IllegalArgumentException("Stored control text is invalid");
            if (!node.get("expectedRevision").isNull() && !node.get("expectedRevision").isTextual()
                    || !node.get("cooldownUntilMillis").isNull() && !node.get("cooldownUntilMillis").isIntegralNumber()
                    || !node.get("updatedAt").isIntegralNumber())
                throw new IllegalArgumentException("Stored control values are invalid");
            return json.readerFor(Control.class)
                    .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .without(tools.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(raw);
        }
        catch (Exception error) { throw new IllegalStateException("Unreadable Worker connection control", error); }
    }
    private String encode(Control value) {
        try { return json.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("Cannot encode Worker connection control", error); }
    }
    private synchronized StatefulRedisConnection<String, String> connection() {
        if (closed) throw new IllegalStateException("Worker connection control is closed");
        if (connection == null || !connection.isOpen()) connection = client.connect(StringCodec.UTF8);
        return connection;
    }
    @Override public synchronized void close() {
        closed = true;
        if (connection != null) connection.close();
    }

    public record Change(String requestId, String expectedRevision, Long cooldownUntilMillis, String reason) {
        public Change {
            if (requestId == null || requestId.isBlank() || requestId.length() > 128
                    || expectedRevision != null && (expectedRevision.isBlank() || expectedRevision.length() > 128)
                    || cooldownUntilMillis != null && (cooldownUntilMillis <= 0 || cooldownUntilMillis > 9_007_199_254_740_991L)
                    || reason == null || reason.length() > 256)
                throw new IllegalArgumentException("Invalid Worker connection control request");
        }
    }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Control(String revision, String requestId, String expectedRevision, Long cooldownUntilMillis,
                          String reason, String actorId, long updatedAt) {
        public Control {
            new Change(requestId, expectedRevision, cooldownUntilMillis, reason);
            if (revision == null || revision.isBlank() || actorId == null || actorId.isBlank() || updatedAt < 0)
                throw new IllegalArgumentException("Invalid stored Worker connection control");
        }
        public boolean cooling(long now) { return cooldownUntilMillis != null && cooldownUntilMillis > now; }
        boolean matches(Change change, String actor) {
            return Objects.equals(expectedRevision, change.expectedRevision())
                    && Objects.equals(cooldownUntilMillis, change.cooldownUntilMillis())
                    && reason.equals(change.reason()) && actorId.equals(actor);
        }
    }
    public static final class Conflict extends RuntimeException { }
}
