package com.xa.mass.kernel.delivery.redis;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.classifyDeliveryReportOutcomeCode;

import com.xa.mass.kernel.KernelOperationNotImplementedException;
import com.xa.mass.kernel.delivery.WorkerResultRuntime;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReportOutcomeClass;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class RedisWorkerResultRuntime
        implements WorkerResultRuntime, AutoCloseable {

    private final RedisClient redisClient;
    private final WorkerDeliveryCodec codec;
    private final RedisKeyspace keyspace;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisWorkerResultRuntime(
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
    public int appendWorkerResults(List<DeliveryReport> results) {
        if (results == null) {
            throw new IllegalArgumentException("results must be present");
        }
        if (results.isEmpty()) {
            return 0;
        }
        Map<DeliveryReportOutcomeClass, List<String>> grouped =
                new EnumMap<>(DeliveryReportOutcomeClass.class);
        for (DeliveryReport result : results) {
            DeliveryReportOutcomeClass outcomeClass =
                    classifyDeliveryReportOutcomeCode(result.outcomeCode());
            if (outcomeClass == null) {
                throw new IllegalArgumentException(
                        "DeliveryReport outcome code is invalid"
                );
            }
            grouped.computeIfAbsent(
                    outcomeClass,
                    ignored -> new ArrayList<>()
            ).add(codec.encodeDeliveryReport(result));
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
    public List<DeliveryReport> consumeWorkerResults(
            DeliveryReportOutcomeClass outcomeClass,
            int limit
    ) {
        throw new KernelOperationNotImplementedException(
                "WorkerResultRuntime",
                "consume_worker_results"
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

    private String resultKey(DeliveryReportOutcomeClass outcomeClass) {
        return keyspace.base() + ":result:routing:"
                + switch (outcomeClass) {
                    case SUCCESS -> "success";
                    case WORKER_FAILURE -> "worker-failure";
                    case ADAPTER_REJECTION -> "adapter-rejection";
                };
    }
}
