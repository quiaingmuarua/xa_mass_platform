package com.xa.mass.kernel.delivery.redis;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.classifyOutcomeCode;

import com.xa.mass.kernel.KernelOperationNotImplementedException;
import com.xa.mass.kernel.delivery.SeedResultRuntime;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultOutcomeClass;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class RedisSeedResultRuntime
        implements SeedResultRuntime, AutoCloseable {

    private final RedisClient redisClient;
    private final WorkerDeliveryCodec codec;
    private final String prefix;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisSeedResultRuntime(
            RedisClient redisClient,
            WorkerDeliveryCodec codec,
            String prefix
    ) {
        if (redisClient == null || codec == null) {
            throw new IllegalArgumentException(
                    "redisClient and codec must be present"
            );
        }
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must be non-blank");
        }
        this.redisClient = redisClient;
        this.codec = codec;
        this.prefix = prefix;
    }

    @Override
    public int appendSeedResults(List<SeedResult> results) {
        if (results == null) {
            throw new IllegalArgumentException("results must be present");
        }
        if (results.isEmpty()) {
            return 0;
        }
        Map<SeedResultOutcomeClass, List<String>> grouped =
                new EnumMap<>(SeedResultOutcomeClass.class);
        for (SeedResult result : results) {
            SeedResultOutcomeClass outcomeClass =
                    classifyOutcomeCode(result.outcomeCode());
            if (outcomeClass == null) {
                throw new IllegalArgumentException(
                        "SeedResult outcome code is invalid"
                );
            }
            grouped.computeIfAbsent(
                    outcomeClass,
                    ignored -> new ArrayList<>()
            ).add(codec.encodeSeedResult(result));
        }
        grouped.forEach((outcomeClass, encodedResults) ->
                commands().rpush(
                        resultKey(outcomeClass),
                        encodedResults.toArray(String[]::new)
                )
        );
        return results.size();
    }

    @Override
    public List<SeedResult> consumeSeedResults(
            SeedResultOutcomeClass outcomeClass,
            int limit
    ) {
        throw new KernelOperationNotImplementedException(
                "SeedResultRuntime",
                "consume_seed_results"
        );
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

    private String resultKey(SeedResultOutcomeClass outcomeClass) {
        return "rr:" + prefix + ":seed-results:"
                + switch (outcomeClass) {
                    case SUCCESS -> "success";
                    case WORKER_FAILURE -> "worker-failure";
                    case ADAPTER_REJECTION -> "adapter-rejection";
                };
    }
}
