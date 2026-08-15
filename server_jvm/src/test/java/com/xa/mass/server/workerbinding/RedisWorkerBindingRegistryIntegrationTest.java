package com.xa.mass.server.workerbinding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("redis-owner")
class RedisWorkerBindingRegistryIntegrationTest {

    private static final String REDIS_URL =
            System.getenv("KERNEL_DESIGN_REDIS_URL");
    private static final String WORKER_ID =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";
    private String prefix;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisWorkerBindingRegistry registry;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(
                REDIS_URL != null && !REDIS_URL.isBlank(),
                "KERNEL_DESIGN_REDIS_URL is not configured"
        );
        prefix = "worker-binding-" + UUID.randomUUID();
        redisClient = RedisClient.create(REDIS_URL);
        connection = redisClient.connect(StringCodec.UTF8);
        registry = new RedisWorkerBindingRegistry(redisClient, prefix);
    }

    @AfterEach
    void tearDown() {
        if (connection != null) {
            var keys = connection.sync().keys("wi:{" + prefix + "}:*");
            if (!keys.isEmpty()) {
                connection.sync().del(keys.toArray(String[]::new));
            }
        }
        if (registry != null) {
            registry.close();
        }
        if (connection != null) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void bindingUsesStableSha256BucketAndNeverOverwrites() {
        assertThat(RedisWorkerBindingRegistry.bucket(WORKER_ID))
                .isEqualTo("57");
        assertThat(registry.bindingKey(WORKER_ID)).isEqualTo(
                "wi:{" + prefix + "}:worker-bindings:57"
        );

        assertThat(registry.bindIfAbsent(WORKER_ID, "websocket-a"))
                .isEqualTo("websocket-a");
        assertThat(registry.bindIfAbsent(WORKER_ID, "socket-a"))
                .isEqualTo("websocket-a");
        assertThat(registry.getEndpointManagerId(WORKER_ID))
                .isEqualTo("websocket-a");
    }

    @Test
    void concurrentDifferentEndpointsObserveOnePersistedWinner()
            throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<String>>();
            for (int index = 0; index < 32; index++) {
                String endpoint = index % 2 == 0
                        ? "websocket-a"
                        : "socket-a";
                futures.add(executor.submit(() ->
                        registry.bindIfAbsent(WORKER_ID, endpoint)));
            }
            var observed = new HashSet<String>();
            for (Future<String> future : futures) {
                observed.add(future.get());
            }
            assertThat(observed).hasSize(1);
            assertThat(observed.iterator().next())
                    .isIn("websocket-a", "socket-a");
            assertThat(registry.getEndpointManagerId(WORKER_ID))
                    .isEqualTo(observed.iterator().next());
        }
    }

    @Test
    void boundedBatchReadPreservesOrderAcrossBucketsAndMissingValues() {
        String sameBucket = workerIdInBucket(
                RedisWorkerBindingRegistry.bucket(WORKER_ID),
                "same"
        );
        String otherBucket = workerIdOutsideBucket(
                RedisWorkerBindingRegistry.bucket(WORKER_ID),
                "other"
        );
        String missing = "missing-worker";
        registry.bindIfAbsent(WORKER_ID, "websocket-a");
        registry.bindIfAbsent(sameBucket, "socket-a");
        registry.bindIfAbsent(otherBucket, "websocket-b");

        assertThat(registry.getEndpointManagerIds(List.of(
                otherBucket,
                missing,
                WORKER_ID,
                sameBucket
        ))).containsExactly(
                entry(otherBucket, "websocket-b"),
                entry(missing, null),
                entry(WORKER_ID, "websocket-a"),
                entry(sameBucket, "socket-a")
        );
    }

    private static String workerIdInBucket(
            String bucket,
            String prefix
    ) {
        for (int index = 0; index < 10_000; index++) {
            String candidate = prefix + "-" + index;
            if (RedisWorkerBindingRegistry.bucket(candidate).equals(bucket)) {
                return candidate;
            }
        }
        throw new AssertionError("Could not find same-bucket Worker ID");
    }

    private static String workerIdOutsideBucket(
            String bucket,
            String prefix
    ) {
        for (int index = 0; index < 10_000; index++) {
            String candidate = prefix + "-" + index;
            if (!RedisWorkerBindingRegistry.bucket(candidate).equals(bucket)) {
                return candidate;
            }
        }
        throw new AssertionError("Could not find cross-bucket Worker ID");
    }
}
