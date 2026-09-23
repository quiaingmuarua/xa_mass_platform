package com.xa.mass.kernel.delivery.redis;

import com.xa.mass.kernel.delivery.TaskEvidenceRuntime;
import com.xa.mass.kernel.delivery.TaskEvidenceRuntime.TaskEvidenceType;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.ArrayList;
import java.util.List;

public final class RedisTaskEvidenceRuntime
        implements TaskEvidenceRuntime, AutoCloseable {

    private final RedisClient redisClient;
    private final WorkerDeliveryCodec codec;
    private final RedisKeyspace keyspace;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisTaskEvidenceRuntime(
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
    public int appendTaskEvidence(
            TaskEvidenceType evidenceType,
            List<DeliveryReport> reports
    ) {
        if (evidenceType == null) {
            throw new IllegalArgumentException(
                    "evidenceType must be present"
            );
        }
        if (reports == null) {
            throw new IllegalArgumentException("reports must be present");
        }
        if (reports.isEmpty()) {
            return 0;
        }
        List<String> encodedReports = new ArrayList<>(reports.size());
        for (DeliveryReport result : reports) {
            if (result == null) {
                throw new IllegalArgumentException(
                        "reports must not contain null"
                );
            }
            encodedReports.add(codec.encodeDeliveryReport(result));
        }
        commands().rpush(
                evidenceKey(evidenceType),
                encodedReports.toArray(String[]::new)
        );
        return reports.size();
    }

    @Override
    public List<DeliveryReport> consumeTaskEvidence(
            TaskEvidenceType evidenceType,
            int limit
    ) {
        if (evidenceType == null) {
            throw new IllegalArgumentException(
                    "evidenceType must be present"
            );
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        List<String> encoded = commands().lpop(
                evidenceKey(evidenceType),
                limit
        );
        if (encoded == null || encoded.isEmpty()) {
            return List.of();
        }
        List<DeliveryReport> reports = new ArrayList<>(encoded.size());
        for (String value : encoded) {
            DeliveryReport result = codec.decodeDeliveryReport(value);
            if (result != null) {
                reports.add(result);
            }
        }
        return List.copyOf(reports);
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

    private String evidenceKey(TaskEvidenceType evidenceType) {
        return keyspace.base() + ":result:routing:"
                + switch (evidenceType) {
                    case EXECUTION_SUCCESS -> "success";
                    case EXECUTION_FAILURE -> "failure";
                    case OUTCOME_OBSERVATION -> "observation";
                };
    }
}
