package com.xa.mass.kernel.delivery.redis;

import com.xa.mass.kernel.delivery.TaskResultRuntime;
import com.xa.mass.kernel.delivery.TaskResultRuntime.TaskResultClass;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.ArrayList;
import java.util.List;

public final class RedisTaskResultRuntime
        implements TaskResultRuntime, AutoCloseable {

    private final RedisClient redisClient;
    private final WorkerDeliveryCodec codec;
    private final RedisKeyspace keyspace;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisTaskResultRuntime(
            RedisClient redisClient,
            WorkerDeliveryCodec codec,
            RedisKeyspace keyspace
    ) {
        if (redisClient == null || codec == null) {
            throw new IllegalArgumentException(
                    "redisClient and codec must be present"
            );
        }
        this.redisClient = redisClient;
        this.codec = codec;
        this.keyspace = java.util.Objects.requireNonNull(
                keyspace,
                "keyspace"
        );
    }

    @Override
    public int appendTaskResults(
            TaskResultClass resultClass,
            List<DeliveryReport> results
    ) {
        if (resultClass == null) {
            throw new IllegalArgumentException(
                    "resultClass must be present"
            );
        }
        if (results == null) {
            throw new IllegalArgumentException("results must be present");
        }
        if (results.isEmpty()) {
            return 0;
        }
        List<String> encodedResults = new ArrayList<>(results.size());
        for (DeliveryReport result : results) {
            if (result == null) {
                throw new IllegalArgumentException(
                        "results must not contain null"
                );
            }
            encodedResults.add(codec.encodeDeliveryReport(result));
        }
        commands().rpush(
                resultKey(resultClass),
                encodedResults.toArray(String[]::new)
        );
        return results.size();
    }

    @Override
    public List<DeliveryReport> consumeTaskResults(
            TaskResultClass resultClass,
            int limit
    ) {
        if (resultClass == null) {
            throw new IllegalArgumentException(
                    "resultClass must be present"
            );
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        List<String> encoded = commands().lpop(
                resultKey(resultClass),
                limit
        );
        if (encoded == null || encoded.isEmpty()) {
            return List.of();
        }
        List<DeliveryReport> results = new ArrayList<>(encoded.size());
        for (String value : encoded) {
            DeliveryReport result = codec.decodeDeliveryReport(value);
            if (result != null) {
                results.add(result);
            }
        }
        return List.copyOf(results);
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

    private String resultKey(TaskResultClass resultClass) {
        return keyspace.base() + ":result:routing:"
                + switch (resultClass) {
                    case SUCCESS -> "success";
                    case FAILURE -> "failure";
                };
    }
}
