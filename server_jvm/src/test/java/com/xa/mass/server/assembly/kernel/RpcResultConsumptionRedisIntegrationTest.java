package com.xa.mass.server.assembly.kernel;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.*;

import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.redis.TaskRedisKeys;
import com.xa.mass.kernel.score.redis.RedisTaskItemScoreBandCore;
import com.xa.mass.kernel.score.redis.RedisTaskScoreBandCore;
import com.xa.mass.kernel.task.TaskRuntime.*;
import com.xa.mass.kernel.task.redis.RedisTaskRuntime;
import com.xa.mass.server.testsupport.RedisTestScope;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@Tag("redis-owner")
class RpcResultConsumptionRedisIntegrationTest {
    private RedisTestScope scope;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisTaskScoreBandCore scores;
    private RedisTaskItemScoreBandCore items;
    private RedisTaskRuntime runtime;

    @BeforeEach void start() {
        scope = RedisTestScope.create("rpc_results");
        client = RedisClient.create(REDIS_URL);
        connection = client.connect();
        scores = new RedisTaskScoreBandCore(client, scope.keyspace());
        items = new RedisTaskItemScoreBandCore(client, scope.keyspace());
        runtime = new RedisTaskRuntime(client, scores, items, scope.keyspace(), 60_000);
        create("rpc", TaskIdleDisposition.PARK_WHEN_IDLE);
    }

    @AfterEach void stop() {
        if (connection != null) scope.cleanup(connection.sync());
        if (runtime != null) runtime.close();
        if (items != null) items.close();
        if (scores != null) scores.close();
        if (connection != null) connection.close();
        if (client != null) client.shutdown();
    }

    private void create(String id, TaskIdleDisposition mode) {
        assertThat(runtime.createTask(new TaskDescriptor(id, "group", mode,
                Map.of("priority", "1", "maxRetryTimes", "2"), List.of(), null, Map.of())).status()).isEqualTo(TaskCreationStatus.CREATED);
    }
    private String key(String id) { return TaskRedisKeys.results(scope.keyspace(), id); }
    private void write(String task, String id, long time, String payload) {
        runtime.storeTaskItemSuccessResults(task, Map.of(id, new TaskItemSuccessResult(6, time, payload)));
    }

    @Test void onlyActualRpcWritesRenewTheWholeHashAndTakeDoesNotChangeExecutionResources() {
        long now = System.currentTimeMillis();
        runtime.appendItems("rpc", List.of(new TaskItem("a", "event", now, Map.of(), 5, now + 60_000, new WorkerQuery("workerId", "w"))));
        var taskBefore = scores.getScoreStates(List.of("rpc"));
        var itemBefore = items.getItemScoreStates("rpc", List.of("a"));
        write("rpc", "a", 10, "first");
        runtime.storeTaskItemFailedResults("rpc", List.of("b"));
        assertThat(connection.sync().pttl(key("rpc"))).isBetween(50_000L, 60_000L);
        connection.sync().pexpire(key("rpc"), 10_000);
        runtime.loadTaskItemResults("rpc", List.of("a", "b"));
        write("rpc", "a", 10, "ignored");
        runtime.storeTaskItemFailedResults("rpc", List.of("a", "b"));
        var taken = runtime.takeTaskItemResults("rpc", List.of("a", "missing", "a"));
        assertThat(taken.keySet()).containsExactly("a", "missing");
        assertThat(taken.get("a").opaqueResultPayload()).isEqualTo("first");
        assertThat(taken.get("missing")).isNull();
        assertThat(connection.sync().pttl(key("rpc"))).isBetween(1L, 10_000L);
        assertThat(scores.getScoreStates(List.of("rpc"))).isEqualTo(taskBefore);
        assertThat(items.getItemScoreStates("rpc", List.of("a"))).isEqualTo(itemBefore);
        assertThat(runtime.loadTaskItems("rpc", List.of("a")).get("a")).isNotNull();
        runtime.storeTaskItemFailedResults("rpc", List.of("c"));
        assertThat(connection.sync().pttl(key("rpc"))).isBetween(50_000L, 60_000L);
        assertThat(runtime.takeTaskItemResults("rpc", List.of("b", "c")).values()).allMatch(row -> !row.succeeded());
        assertThat(connection.sync().exists(key("rpc"))).isZero();
        write("rpc", "a", 11, "later");
        assertThat(runtime.takeTaskItemResults("rpc", List.of("a")).get("a").opaqueResultPayload()).isEqualTo("later");
    }

    @Test void idleExpiryCanReclaimUnclaimedResultsWithoutDeletingTaskOrBlockingLaterWrites() throws Exception {
        write("rpc", "a", 10, "first");
        connection.sync().pexpire(key("rpc"), 1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (connection.sync().exists(key("rpc")) != 0 && System.nanoTime() < deadline) Thread.sleep(2);
        assertThat(runtime.takeTaskItemResults("rpc", List.of("a")).get("a")).isNull();
        assertThat(scores.getScoreStates(List.of("rpc")).get("rpc")).isNotNull();
        write("rpc", "a", 11, "again");
        assertThat(connection.sync().pttl(key("rpc"))).isBetween(50_000L, 60_000L);
        assertThat(runtime.loadTaskItemResults("rpc", List.of("a")).get("a").opaqueResultPayload()).isEqualTo("again");
    }

    @Test void validatesTheEntireBatchBeforeDeletionAndRejectsFiniteOrMissingTaskData() {
        write("rpc", "good", 10, "kept");
        connection.sync().hset(key("rpc"), "bad", "{\"code\":\"200\",\"opaqueResultPayload\":\"bad\",\"tag\":99,\"observedAtMillis\":10}");
        assertThatThrownBy(() -> runtime.takeTaskItemResults("rpc", List.of("good", "bad"))).hasMessageContaining("corrupt");
        assertThat(connection.sync().hlen(key("rpc"))).isEqualTo(2);
        create("finite", TaskIdleDisposition.CLOSE_WHEN_IDLE);
        write("finite", "a", 10, "finite");
        assertThat(connection.sync().pttl(key("finite"))).isEqualTo(-1);
        assertThatThrownBy(() -> runtime.takeTaskItemResults("finite", List.of("a"))).hasMessageContaining("consumption");
        connection.sync().hset(TaskRedisKeys.descriptor(scope.keyspace(), "rpc"), "dataPhase", "FROZEN");
        assertThatThrownBy(() -> runtime.takeTaskItemResults("rpc", List.of("good"))).hasMessageContaining("consumption");
        connection.sync().unlink(TaskRedisKeys.descriptor(scope.keyspace(), "rpc"));
        assertThatThrownBy(() -> runtime.takeTaskItemResults("rpc", List.of("good"))).hasMessageContaining("consumption");
        assertThat(connection.sync().hlen(key("rpc"))).isEqualTo(2);
    }

    @Test void competingConsumersAndWritersHaveOneAtomicConsumptionBoundary() throws Exception {
        try (var other = new RedisTaskRuntime(client, scores, items, scope.keyspace(), 60_000);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            write("rpc", "a", 10, "first");
            var start = new CyclicBarrier(2);
            var a = executor.submit(() -> { start.await(); return runtime.takeTaskItemResults("rpc", List.of("a")).get("a"); });
            var b = executor.submit(() -> { start.await(); return other.takeTaskItemResults("rpc", List.of("a")).get("a"); });
            assertThat(java.util.stream.Stream.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS)).filter(Objects::nonNull).count()).isEqualTo(1);
            for (int round = 0; round < 30; round++) {
                write("rpc", "a", 10, "before");
                var race = new CyclicBarrier(2);
                var take = executor.submit(() -> { race.await(); return runtime.takeTaskItemResults("rpc", List.of("a")).get("a"); });
                var put = executor.submit(() -> { race.await(); other.storeTaskItemSuccessResults("rpc", Map.of("a", new TaskItemSuccessResult(6, 11, "after"))); return true; });
                var consumed = take.get(5, TimeUnit.SECONDS);
                put.get(5, TimeUnit.SECONDS);
                var remaining = runtime.takeTaskItemResults("rpc", List.of("a")).get("a");
                if (consumed.opaqueResultPayload().equals("before")) assertThat(remaining.opaqueResultPayload()).isEqualTo("after");
                else { assertThat(consumed.opaqueResultPayload()).isEqualTo("after"); assertThat(remaining).isNull(); }
            }
        }
    }
}
