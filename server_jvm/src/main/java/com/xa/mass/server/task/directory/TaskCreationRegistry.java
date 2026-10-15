package com.xa.mass.server.task.directory;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.jspecify.annotations.Nullable;

/** Retained creation intent, independent of the generated execution identity. */
@Service
public final class TaskCreationRegistry implements AutoCloseable {
    public static final String METADATA_KEY = "server.creationKey";
    private static final String RESERVE = """
            if redis.call('EXISTS', KEYS[1]) == 1 then
              local row = redis.call('HMGET', KEYS[1], 'taskId', 'fingerprint', 'inputOwner')
              if not row[1] or not row[2] or not row[3] then return redis.error_reply('Creation association is corrupt') end
              return row
            end
            redis.call('HSET', KEYS[1], 'taskId', ARGV[1], 'fingerprint', ARGV[2], 'inputOwner', ARGV[3])
            return {ARGV[1], ARGV[2], ARGV[3]}
            """;
    private static final String REMOVE = """
            if redis.call('HGET', KEYS[1], 'taskId') ~= ARGV[1] then return 0 end
            return redis.call('UNLINK', KEYS[1])
            """;
    private final RedisClient redis;
    private final RedisKeyspace space;
    private StatefulRedisConnection<String, String> connection;
    private boolean closed;

    public TaskCreationRegistry(RedisClient redis, RedisKeyspace space) { this.redis = redis; this.space = space; }

    public record Claim(String key, String taskId) { }

    public @Nullable Claim find(String project, String caller, String requestId, String inputOwner, String fingerprint) {
        String identity = identity(project, caller, requestId);
        var row = connection().sync().hgetall(key(identity));
        if (row.isEmpty()) return null;
        if (!row.keySet().equals(java.util.Set.of("taskId", "fingerprint", "inputOwner")))
            throw new IllegalStateException("Creation association is corrupt");
        if (!fingerprint.equals(row.get("fingerprint")) || !inputOwner.equals(row.get("inputOwner")))
            throw new ServerException(ServerErrorCode.TASK_STATE_CONFLICT, "taskCreation.find", "Creation request has different content", null);
        return new Claim(identity, row.get("taskId"));
    }

    public Claim reserve(String project, String caller, String requestId, String inputOwner, String fingerprint) {
        String key = identity(project, caller, requestId);
        try {
            List<?> row = connection().sync().eval(RESERVE, ScriptOutputType.MULTI, new String[]{key(key)},
                    "task-" + UUID.randomUUID().toString().replace("-", ""), fingerprint, inputOwner);
            if (!fingerprint.equals(row.get(1)) || !inputOwner.equals(row.get(2)))
                throw new ServerException(ServerErrorCode.TASK_STATE_CONFLICT, "taskCreation.reserve", "Creation request has different content", null);
            return new Claim(key, String.valueOf(row.getFirst()));
        } catch (ServerException known) { throw known;
        } catch (RuntimeException failure) {
            throw new ServerException(ServerErrorCode.TASK_DATA_UNAVAILABLE, "taskCreation.reserve",
                    "Creation association is unconfirmed; retry the same requestId", failure);
        }
    }

    public boolean isCurrent(Claim claim) { return claim.taskId().equals(connection().sync().hget(key(claim.key()), "taskId")); }

    public void remove(String association, String taskId) {
        Long removed = connection().sync().eval(REMOVE, ScriptOutputType.INTEGER, new String[]{key(association)}, taskId);
        if (removed == null) throw new IllegalStateException("Creation association removal is unconfirmed");
    }

    static String identity(String... parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] value = part.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(value.length).array()); digest.update(value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private String key(String identity) {
        if (identity == null || !identity.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid creation association");
        return space.base() + ":server:task-creation:" + identity;
    }
    private synchronized StatefulRedisConnection<String, String> connection() {
        if (closed) throw new IllegalStateException("Creation registry is closed");
        if (connection == null || !connection.isOpen()) connection = redis.connect();
        return connection;
    }
    @Override public synchronized void close() { closed = true; if (connection != null) connection.close(); }
}
