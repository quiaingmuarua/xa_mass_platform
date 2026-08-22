package com.xa.mass.server.workeridentity;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.server.testsupport.RedisTestScope;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("redis-owner")
class RedisWorkerIdentityRegistryIntegrationTest {

    private RedisTestScope testScope;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisWorkerIdentityRegistry registry;

    @BeforeEach
    void setUp() {
        testScope = RedisTestScope.create("worker_identity");
        redisClient = RedisClient.create(REDIS_URL);
        connection = redisClient.connect(StringCodec.UTF8);
        registry = new RedisWorkerIdentityRegistry(
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
    void concurrentRegistrationReturnsOneStableWorkerId() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<String>>();
            for (int index = 0; index < 32; index++) {
                futures.add(executor.submit(() ->
                        registry.register("group-1", "installation-1")));
            }
            var workerIds = new HashSet<String>();
            for (Future<String> future : futures) {
                workerIds.add(future.get());
            }
            assertThat(workerIds).hasSize(1);
            String workerId = workerIds.iterator().next();
            assertThat(UUID.fromString(workerId).toString())
                    .isEqualTo(workerId);
            assertThat(registry.matches(
                    "group-1",
                    "installation-1",
                    workerId
            )).isTrue();
            assertThat(connection.sync().hget(
                    testScope.keyspace().base()
                            + ":worker:identity:group-1",
                    "installation-1"
            )).isEqualTo(workerId);
        }
    }

    @Test
    void theSameClientKeyIsNamespacedByWorkerGroup() {
        String first = registry.register("group-1", "installation-1");
        String second = registry.register("group-2", "installation-1");

        assertThat(first).isNotEqualTo(second);
        assertThat(registry.matches(
                "group-1",
                "installation-1",
                second
        )).isFalse();
    }
}
