package com.xa.mass.kernel.delivery.redis;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.classifyWorkerResultOutcomeCode;

import com.xa.mass.kernel.KernelOperationNotImplementedException;
import com.xa.mass.kernel.delivery.WorkerResultRuntime;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResultOutcomeClass;
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
    private final String prefix;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisWorkerResultRuntime(
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
    public int appendWorkerResults(List<WorkerResult> results) {
        if (results == null) {
            throw new IllegalArgumentException("results must be present");
        }
        if (results.isEmpty()) {
            return 0;
        }
        Map<WorkerResultOutcomeClass, List<String>> grouped =
                new EnumMap<>(WorkerResultOutcomeClass.class);
        for (WorkerResult result : results) {
            WorkerResultOutcomeClass outcomeClass =
                    classifyWorkerResultOutcomeCode(result.outcomeCode());
            if (outcomeClass == null) {
                throw new IllegalArgumentException(
                        "WorkerResult outcome code is invalid"
                );
            }
            grouped.computeIfAbsent(
                    outcomeClass,
                    ignored -> new ArrayList<>()
            ).add(codec.encodeWorkerResult(result));
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
    public List<WorkerResult> consumeWorkerResults(
            WorkerResultOutcomeClass outcomeClass,
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

    private String resultKey(WorkerResultOutcomeClass outcomeClass) {
        return "rr:" + prefix + ":worker-results:"
                + switch (outcomeClass) {
                    case SUCCESS -> "success";
                    case WORKER_FAILURE -> "worker-failure";
                    case ADAPTER_REJECTION -> "adapter-rejection";
                };
    }
}
