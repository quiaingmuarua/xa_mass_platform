package com.xa.mass.server.task.credit;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.server.credits.CreditModels;
import com.xa.mass.server.credits.CreditModels.BusinessCharge;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Task-scoped admission intent. Receipts and balance remain authoritative in the account. */
@Component
public final class TaskCreditStartStore implements AutoCloseable {
    public record Start(String requestId, String ledgerRequestId, String userId, String actorUserId,
                        long itemCount, BusinessCharge business) {
        public Start {
            requestId = CreditModels.requestId(requestId); ledgerRequestId = CreditModels.requestId(ledgerRequestId);
            userId = CreditModels.userId(userId); actorUserId = CreditModels.userId(actorUserId);
            if (itemCount < 1 || itemCount > CreditModels.MAX_POINTS || business == null)
                throw new IllegalArgumentException("Invalid Task credit intent");
        }
    }
    private static final String SCRIPT = """
            local kind = redis.call('TYPE', KEYS[1]).ok
            if kind ~= 'none' and kind ~= 'hash' then return redis.error_reply('Task credit intent is corrupt') end
            local active = redis.call('HGET', KEYS[1], 'active')
            if kind == 'hash' and (not active or not redis.call('HGET', KEYS[1], 'request:' .. active)) then
              return redis.error_reply('Task credit intent is incomplete')
            end
            if ARGV[1] == 'read' then
              if not active then return nil end
              return redis.call('HGET', KEYS[1], 'request:' .. (ARGV[2] == '' and active or ARGV[2]))
            end
            local previous = redis.call('HGET', KEYS[1], 'request:' .. ARGV[2])
            if previous then return previous end
            if (active or '') ~= ARGV[4] then return 'conflict' end
            redis.call('HSET', KEYS[1], 'active', ARGV[2], 'request:' .. ARGV[2], ARGV[3])
            return ARGV[3]
            """;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RedisClient redis;
    private final RedisKeyspace keyspace;
    private StatefulRedisConnection<String, String> connection;
    private boolean closed;
    public TaskCreditStartStore(RedisClient redis, RedisKeyspace keyspace) { this.redis = redis; this.keyspace = keyspace; }
    public @Nullable Start read(String taskId, @Nullable String requestId) {
        return evaluate(taskId, "read", requestId == null ? "" : CreditModels.requestId(requestId));
    }
    public Start reserve(String taskId, Start start, @Nullable String expectedActive) {
        var result = evaluate(taskId, "reserve", start.requestId(), JSON.writeValueAsString(start), expectedActive == null ? "" : expectedActive);
        if (result == null) throw unavailable(null);
        return result;
    }
    public void remove(String taskId) {
        try { connection().sync().unlink(key(taskId)); }
        catch (RuntimeException failed) { throw unavailable(failed); }
    }
    private @Nullable Start evaluate(String taskId, String... args) {
        try {
            String encoded = connection().sync().eval(SCRIPT, ScriptOutputType.VALUE, new String[]{key(taskId)}, args);
            if (encoded == null) return null;
            if (encoded.equals("conflict")) throw new ServerException(ServerErrorCode.CREDIT_ADJUSTMENT_CONFLICT, "taskCredits.reserve", null, null);
            var start = JSON.readValue(encoded, Start.class);
            if (!start.business().taskId().equals(taskId)) throw new IllegalStateException("Task credit association differs");
            return start;
        } catch (ServerException known) { throw known;
        } catch (RuntimeException failed) { throw unavailable(failed); }
    }
    private String key(String taskId) {
        return keyspace.base() + ":server:task-credit-start:" + UUID.nameUUIDFromBytes(taskId.getBytes(StandardCharsets.UTF_8));
    }
    private synchronized StatefulRedisConnection<String, String> connection() {
        if (closed) throw new IllegalStateException("Task credit store is closed");
        if (connection == null || !connection.isOpen()) connection = redis.connect();
        return connection;
    }
    @Override public synchronized void close() { closed = true; if (connection != null) connection.close(); }
    private static ServerException unavailable(Throwable cause) {
        return new ServerException(ServerErrorCode.CREDIT_UNAVAILABLE, "taskCredits.store", null, cause);
    }
}
