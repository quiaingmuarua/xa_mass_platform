package com.xa.mass.server;

import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.storage.jdbc.JdbcStorageRuntime;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.task.runtime.starter.TaskRuntimeBackendKind;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "sample.worker.auto-start=false",
                "mass.control-plane.seed.enabled=true",
                "mass.control-plane.seed.operator-credentials-location=classpath:control-plane-seed/operator-credentials.json",
                "mass.engine.assignment-retry-delay-millis=100",
                "mass.engine.runtime-ready-dispatch-idle-backoff-max-millis=500",
                "mass.engine.lease-watchdog-interval-seconds=1",
                "mass.engine.task-message-lease-seconds=2"
        }
)
@ActiveProfiles("durable-local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServerDurableLocalProfileContextTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final String REDIS_HOST = System.getProperty("mass.test.redis.host", "127.0.0.1");
    private static final int REDIS_PORT = Integer.getInteger("mass.test.redis.port", 6379);
    private static final String REDIS_URI = "redis://" + REDIS_HOST + ":" + REDIS_PORT + "/0";
    private static final String NAMESPACE = "xa:mass:test:durable-local-profile:" + UUID.randomUUID();
    private static final Path SQLITE_DB = createTempSqliteDbPath();

    @Autowired
    private JdbcStorageRuntime jdbcStorageRuntime;

    @Autowired
    private ApplicationContext applicationContext;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
        registerJdbcStorageProperties(registry, "jdbc-sqlite", "jdbc:sqlite:" + SQLITE_DB, "", "");
        registry.add("spring.redis.host", () -> REDIS_HOST);
        registry.add("spring.redis.port", () -> REDIS_PORT);
        registry.add("spring.redis.database", () -> 0);
        registry.add("mass.runtime.redis.namespace", () -> NAMESPACE + ":runtime");
        registry.add("mass.transport.polling.buffer.redis.namespace", () -> NAMESPACE + ":polling-delivery");
        registry.add("mass.transport.endpoint-lease.redis.namespace", () -> NAMESPACE + ":endpoint-lease");
    }

    @AfterAll
    static void cleanupRedisNamespace() {
        RedisClient redisClient = RedisClient.create(REDIS_URI);
        try (StatefulRedisConnection<String, String> connection = redisClient.connect()) {
            List<String> keys = connection.sync().keys(NAMESPACE + ":*");
            if (!keys.isEmpty()) {
                connection.sync().del(keys.toArray(String[]::new));
            }
        } finally {
            redisClient.shutdown();
        }
    }

    @Test
    void durableLocalStartsWithSqliteAndRedisRuntime() {
        assertThat(jdbcStorageRuntime.isEnabled()).isTrue();
        assertThat(applicationContext.containsBean("taskWorkRuntime")).isFalse();
        assertThat(applicationContext.containsBean("taskResultRuntime")).isFalse();
        assertThat(runtimeEngineConfig().getTaskRuntimeBootstrapConfig().backendKind())
                .isEqualTo(TaskRuntimeBackendKind.REDIS);
        assertThat(runtimeEngineConfig().getTaskRuntimeBootstrapConfig().redisNamespace())
                .isEqualTo(NAMESPACE + ":runtime:task-runtime");
        assertThat(Files.exists(SQLITE_DB.resolveSibling(SQLITE_DB.getFileName() + ".schema.sha256"))).isTrue();
    }

    private EngineConfig runtimeEngineConfig() {
        Object delegate = ReflectionTestUtils.getField(app, "delegate");
        Object engine = ReflectionTestUtils.getField(delegate, "engine");
        return (EngineConfig) ReflectionTestUtils.getField(engine, "config");
    }

    private static Path createTempSqliteDbPath() {
        try {
            Path db = Files.createTempDirectory("xa-mass-durable-local-profile-").resolve("xa_mass.db");
            Files.writeString(db, "stale-local-db-without-sidecar");
            return db;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create durable-local SQLite test path", e);
        }
    }
}
