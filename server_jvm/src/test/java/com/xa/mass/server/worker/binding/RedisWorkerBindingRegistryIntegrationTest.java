package com.xa.mass.server.worker.binding;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.xa.mass.server.testsupport.RedisTestScope;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("redis-owner")
class RedisWorkerBindingRegistryIntegrationTest {

    private static final String WORKER_ID =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";
    private RedisTestScope testScope;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisWorkerBindingRegistry registry;

    @BeforeEach
    void setUp() {
        testScope = RedisTestScope.create("worker_binding");
        redisClient = RedisClient.create(REDIS_URL);
        connection = redisClient.connect(StringCodec.UTF8);
        registry = new RedisWorkerBindingRegistry(
                redisClient,
                testScope.keyspace()
        );
    }

    @AfterEach
    void tearDown() {
        if (connection != null) {
            testScope.cleanup(connection.sync());
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
                testScope.keyspace().base() + ":worker:binding:57"
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

        assertThat(registry.getEndpointManagerIdsAsync(List.of(
                otherBucket,
                missing,
                WORKER_ID,
                sameBucket
        )).toCompletableFuture().join()).containsExactly(
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
