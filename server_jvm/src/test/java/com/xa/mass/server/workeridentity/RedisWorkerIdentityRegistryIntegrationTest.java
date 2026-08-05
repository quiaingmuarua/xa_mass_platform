package com.xa.mass.server.workeridentity;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class RedisWorkerIdentityRegistryIntegrationTest {

    private static final String REDIS_URL =
            System.getenv("KERNEL_DESIGN_REDIS_URL");
    private String prefix;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisWorkerIdentityRegistry registry;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(
                REDIS_URL != null && !REDIS_URL.isBlank(),
                "KERNEL_DESIGN_REDIS_URL is not configured"
        );
        prefix = "worker-identity-" + UUID.randomUUID();
        redisClient = RedisClient.create(REDIS_URL);
        connection = redisClient.connect(StringCodec.UTF8);
        registry = new RedisWorkerIdentityRegistry(redisClient, prefix);
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
