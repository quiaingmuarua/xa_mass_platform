package com.xa.mass.kernel.worker.redis;

import com.xa.mass.kernel.worker.WorkerPropertyIndex;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;

/** Creates per-field HASH projections over one Redis connection. */
public final class RedisHashWorkerPropertyIndexProvider
        implements AutoCloseable {

    private final RedisClient redisClient;
    private final String prefix;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisHashWorkerPropertyIndexProvider(
            RedisClient redisClient,
            String prefix
    ) {
        if (redisClient == null) {
            throw new IllegalArgumentException("redisClient must be present");
        }
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must be non-blank");
        }
        this.redisClient = redisClient;
        this.prefix = prefix;
    }

    public WorkerPropertyIndex create(String propertyField) {
        if (!WorkerRedisSupport.validIndexField(propertyField)) {
            throw new IllegalArgumentException(
                    "property index fields must use index.*"
            );
        }
        return new RedisHashWorkerPropertyIndex(this, propertyField);
    }

    String prefix() {
        return prefix;
    }

    RedisCommands<String, String> commands() {
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
}
