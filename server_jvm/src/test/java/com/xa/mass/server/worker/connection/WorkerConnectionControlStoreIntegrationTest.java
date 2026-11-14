package com.xa.mass.server.worker.connection;

import static org.assertj.core.api.Assertions.*;
import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import com.xa.mass.server.testsupport.RedisTestScope;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

@Tag("redis-owner")
class WorkerConnectionControlStoreIntegrationTest {
    private RedisTestScope scope;
    private RedisClient client;
    private StatefulRedisConnection<String, String> redis;
    private WorkerConnectionControlStore store;
    @BeforeEach void setup() {
        scope = RedisTestScope.create("worker_connection_control");
        client = RedisClient.create(REDIS_URL);
        redis = client.connect();
        store = new WorkerConnectionControlStore(client, scope.keyspace(), JsonMapper.builder().build());
    }
    @AfterEach void cleanup() {
        if (store != null) store.close();
        if (redis != null) { scope.cleanup(redis.sync()); redis.close(); }
        if (client != null) client.shutdown();
    }
    @Test void releaseRetainsRevisionAndRejectsLateOldWriteAcrossRestart() {
        var first = new WorkerConnectionControlStore.Change("first", null, 1000L, "maintenance");
        var cold = store.update("g", "w", first, "admin", 100);
        assertThat(store.update("g", "w", first, "admin", 2000)).isEqualTo(cold);
        var clear = store.update("g", "w", new WorkerConnectionControlStore.Change("release", cold.revision(), null, "ready"), "admin", 200);
        store.close();
        store = new WorkerConnectionControlStore(client, scope.keyspace(), JsonMapper.builder().build());
        assertThat(store.read("g", "w")).isEqualTo(clear);
        assertThat(clear.cooling(201)).isFalse();
        assertThatThrownBy(() -> store.update("g", "w", first, "admin", 300))
                .isInstanceOf(WorkerConnectionControlStore.Conflict.class);
        assertThat(store.readAllAsync("g", List.of("w", "absent")).join()).containsOnlyKeys("w");
        assertThat(store.readAllAsync("another", List.of("w")).join()).isEmpty();
    }
    @Test void competingProcessesPublishExactlyOneCompleteDecision() throws Exception {
        try (var other = new WorkerConnectionControlStore(client, scope.keyspace(), JsonMapper.builder().build());
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            var one = executor.submit(() -> updateAfter(start, store, "one"));
            var two = executor.submit(() -> updateAfter(start, other, "two"));
            start.countDown();
            assertThat(List.of(one.get(), two.get())).containsExactlyInAnyOrder("updated", "conflict");
            assertThat(store.read("g", "w").requestId()).isIn("one", "two");
            assertThat(redis.sync().hlen(scope.keyspace().base() + ":server:worker-connection-control:g")).isEqualTo(1);
        }
    }
    private String updateAfter(CountDownLatch start, WorkerConnectionControlStore target, String request) throws Exception {
        start.await();
        try { target.update("g", "w", new WorkerConnectionControlStore.Change(request, null, 1000L, request), "admin", 100); return "updated"; }
        catch (WorkerConnectionControlStore.Conflict conflict) { return "conflict"; }
    }
    @Test void responseReconciliationDoesNotRefreshExpiryAndBadInputDoesNotWrite() {
        var request = new WorkerConnectionControlStore.Change("once", null, 1000L, "maintenance");
        store.update("g", "w", request, "admin", 100); // caller lost this response
        var saved = store.read("g", "w");
        assertThat(store.update("g", "w", request, "admin", 900)).isEqualTo(saved);
        assertThat(saved.cooling(999)).isTrue();
        assertThat(saved.cooling(1000)).isFalse();
        assertThatThrownBy(() -> store.update("g", "w", new WorkerConnectionControlStore.Change("once", null, 2000L, "changed"), "admin", 200))
                .isInstanceOf(WorkerConnectionControlStore.Conflict.class);
        assertThatThrownBy(() -> store.update("g", "w", request, "different-admin", 200))
                .isInstanceOf(WorkerConnectionControlStore.Conflict.class);
        assertThatThrownBy(() -> store.update("g", "w", new WorkerConnectionControlStore.Change("expired", saved.revision(), 200L, ""), "admin", 200))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.read("g", "w")).isEqualTo(saved);
    }
    @Test void corruptStateIsNotReplacedOrTreatedAsAllowed() {
        String key = scope.keyspace().base() + ":server:worker-connection-control:g";
        redis.sync().hset(key, "w", "{}");
        assertThatThrownBy(() -> store.read("g", "w")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.readAllAsync("g", List.of("w")).join()).hasCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.update("g", "w", new WorkerConnectionControlStore.Change("q", null, null, ""), "admin", 100))
                .isInstanceOf(IllegalStateException.class);
        assertThat(redis.sync().hget(key, "w")).isEqualTo("{}");
    }

    @Test void missingNullableDeadlineOrTimestampCannotBecomeAnAllowedDefault() {
        String key = scope.keyspace().base() + ":server:worker-connection-control:g";
        store.update("g", "w", new WorkerConnectionControlStore.Change("q", null, 1000L, "maintenance"), "admin", 100);
        String complete = redis.sync().hget(key, "w");
        for (String missing : List.of("cooldownUntilMillis", "updatedAt", "expectedRevision")) {
            var document = (tools.jackson.databind.node.ObjectNode) JsonMapper.builder().build().readTree(complete);
            document.remove(missing);
            redis.sync().hset(key, "w", document.toString());
            assertThatThrownBy(() -> store.read("g", "w")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> store.readAllAsync("g", List.of("w")).join()).hasCauseInstanceOf(IllegalStateException.class);
        }
    }
}
