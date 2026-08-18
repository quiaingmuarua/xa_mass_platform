package com.xa.mass.server.workerdelivery.workerchange;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.List;

/** Redis LIST inbox with an atomic owner-local capacity fence. */
public final class RedisWorkerChangeInbox
        implements WorkerChangeInbox, AutoCloseable {

    private static final String BOUNDED_APPEND_SCRIPT = """
            local capacity = tonumber(ARGV[1])
            local remaining = capacity - redis.call('LLEN', KEYS[1])
            if remaining <= 0 then
              return 0
            end
            local offered = #ARGV - 1
            local accepted = math.min(remaining, offered)
            for index = 1, accepted do
              redis.call('RPUSH', KEYS[1], ARGV[index + 1])
            end
            return accepted
            """;

    private final RedisClient redisClient;
    private final WorkerDeliveryCodec codec;
    private final String prefix;
    private final int capacity;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisWorkerChangeInbox(
            RedisClient redisClient,
            WorkerDeliveryCodec codec,
            String prefix,
            int capacity
    ) {
        if (redisClient == null || codec == null) {
            throw new IllegalArgumentException(
                    "redisClient and codec must be present"
            );
        }
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must be non-blank");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.redisClient = redisClient;
        this.codec = codec;
        this.prefix = prefix;
        this.capacity = capacity;
    }

    @Override
    public int append(List<DeliveryReport> reports) {
        if (reports == null) {
            throw new IllegalArgumentException("reports must be present");
        }
        if (reports.size() > MAX_APPEND_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "Worker change report batch exceeds 100"
            );
        }
        if (reports.isEmpty()) {
            return 0;
        }
        String[] arguments = new String[reports.size() + 1];
        arguments[0] = Integer.toString(capacity);
        for (int index = 0; index < reports.size(); index++) {
            DeliveryReport report = reports.get(index);
            if (report == null) {
                throw new IllegalArgumentException(
                        "reports must not contain null"
                );
            }
            arguments[index + 1] = codec.encodeDeliveryReport(report);
        }
        Long accepted = commands().eval(
                BOUNDED_APPEND_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[]{inboxKey()},
                arguments
        );
        return Math.toIntExact(accepted);
    }

    private RedisCommands<String, String> commands() {
        return connection().sync();
    }

    private StatefulRedisConnection<String, String> connection() {
        StatefulRedisConnection<String, String> current = connection;
        if (current == null || !current.isOpen()) {
            synchronized (this) {
                current = connection;
                if (current == null || !current.isOpen()) {
                    current = redisClient.connect(StringCodec.UTF8);
                    connection = current;
                }
            }
        }
        return current;
    }

    @Override
    public void close() {
        StatefulRedisConnection<String, String> current = connection;
        if (current != null) {
            current.close();
        }
    }

    private String inboxKey() {
        return "we:{" + prefix + "}:route-change-inbox";
    }
}
