package com.xa.mass.server.assembly.kernel;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.*;

import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.redis.TaskRedisKeys;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.redis.RedisTaskScoreBandCore;
import com.xa.mass.kernel.score.redis.RedisTaskItemScoreBandCore;
import com.xa.mass.kernel.task.TaskRuntime.*;
import com.xa.mass.kernel.task.redis.RedisTaskRuntime;
import com.xa.mass.server.testsupport.RedisTestScope;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;

@Tag("redis-owner")
class FiniteTaskRetentionRedisIntegrationTest {
    private RedisTestScope scope;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisTaskRuntime runtime;
    private RedisTaskScoreBandCore scores;
    private RedisTaskItemScoreBandCore items;
    private final AtomicLong clock = new AtomicLong(2_000_000_000_000L);

    @BeforeEach void start() {
        scope = RedisTestScope.create("finite_retention");
        client = RedisClient.create(REDIS_URL);
        connection = client.connect();
        scores = new RedisTaskScoreBandCore(client, scope.keyspace(), clock::get);
        items = new RedisTaskItemScoreBandCore(client, scope.keyspace(), clock::get);
        runtime = new RedisTaskRuntime(client, scores, items, scope.keyspace(), java.time.Duration.ofDays(7).toMillis(), clock::get);
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
    private TaskItem input(String id) {
        return new TaskItem(id, "test.event", clock.get(), Map.of("value", id), 5, clock.get() + 60_000, new WorkerQuery("workerId", "worker"));
    }
    private long close(String id) { return scores.closeScore(id).score(); }

    @Test void terminationIsFirstOwnerTimeAndRepeatedCloseDoesNotRenewIt() {
        create("task", TaskIdleDisposition.CLOSE_WHEN_IDLE);
        long first = clock.get();
        close("task");
        clock.addAndGet(5_000);
        assertThat(scores.closeScore("task").status()).isEqualTo(TaskScoreBandCore.TaskScoreTransitionStatus.NOOP);
        assertThat(scores.getScoreStates(List.of("task")).get("task").terminatedAtMillis()).isEqualTo(first);
    }

    @Test void cursorSurvivesEqualTimesAndDeletionOfEveryPreviousPage() {
        for (int i = 0; i < 213; i++) { create("task-" + i, TaskIdleDisposition.CLOSE_WHEN_IDLE); close("task-" + i); }
        for (int i = 0; i < 21; i++) connection.sync().zadd(TaskRedisKeys.scores(scope.keyspace()), -clock.get() + .5, "corrupt-" + i);
        String cursor = "0";
        var seen = new HashSet<String>();
        do {
            var page = scores.scanTerminalTasks(clock.get(), cursor, 17);
            for (var task : page.tasks()) {
                assertThat(seen.add(task.taskId())).isTrue();
                runtime.beginTaskPurge(task.taskId(), task.score());
                runtime.purgeTaskData(task.taskId(), task.score());
                runtime.finishTaskPurge(task.taskId(), task.score());
            }
            cursor = page.nextCursor();
        } while (!cursor.equals("0"));
        assertThat(seen).hasSize(213);
    }

    @Test void admittedLateWriterCannotCrossFreezeOrRecreatePurgedResources() throws Exception {
        create("task", TaskIdleDisposition.CLOSE_WHEN_IDLE);
        var input = input("one");
        runtime.appendItems("task", List.of(input));
        long terminal = close("task");
        runtime.storeTaskItemSuccessResults("task", Map.of("one", new TaskItemSuccessResult(6, clock.get(), "grace")));
        var admitted = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = executor.submit(() -> {
                assertThat(runtime.loadTaskDataStates(List.of("task")).get("task").phase()).isEqualTo(TaskDataPhase.OPEN);
                admitted.countDown();
                if (!commit.await(10, TimeUnit.SECONDS)) throw new AssertionError("freeze did not finish");
                runtime.storeTaskItemSuccessResults("task", Map.of("one", new TaskItemSuccessResult(9, clock.get(), "late")));
                runtime.storeTaskItemFailedResults("task", List.of("new"));
                runtime.appendItems("task", List.of(input("new")));
                items.initializeItemScores("task", Map.of("new", clock.get()), 1);
                items.promoteItemOutcomes("task", Map.of("one", new TaskItemScoreBandCore.TaskItemOutcomeTarget(9, clock.get())));
                return null;
            });
            assertThat(admitted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(runtime.freezeTaskData("task", terminal)).isTrue();
            commit.countDown();
            writer.get(10, TimeUnit.SECONDS);
        } finally { commit.countDown(); }
        assertThat(runtime.loadTaskItemResults("task", List.of("one")).get("one").opaqueResultPayload()).isEqualTo("grace");
        assertThat(items.observeItemScoreCounts(List.of("task")).get("task").countsByTag().get(1)).isEqualTo(1);
        assertThat(runtime.releaseTaskPayloads("task", terminal)).isTrue();
        assertThat(runtime.releaseTaskPayloads("task", terminal)).isFalse();
        assertThat(items.observeItemScoreCounts(List.of("task")).get("task").total()).isEqualTo(1);
        runtime.beginTaskPurge("task", terminal);
        assertThatThrownBy(() -> runtime.finishTaskPurge("task", terminal)).hasMessageContaining("incomplete");
        runtime.purgeTaskData("task", terminal);
        runtime.finishTaskPurge("task", terminal);
        assertThat(runtime.finishTaskPurge("task", terminal)).isFalse();
        runtime.storeTaskItemSuccessResults("task", Map.of("one", new TaskItemSuccessResult(9, clock.get(), "after-delete")));
        items.initializeItemScores("task", Map.of("one", clock.get()), 1);
        assertThat(connection.sync().exists(TaskRedisKeys.results(scope.keyspace(), "task"), TaskRedisKeys.itemScores(scope.keyspace(), "task"))).isZero();
        assertThat(scores.getScoreStates(List.of("task")).get("task")).isNull();
    }

    @Test void onlyFiniteTerminalExactIdentityCanBeReclaimedAndOtherTasksSurvive() {
        create("finite", TaskIdleDisposition.CLOSE_WHEN_IDLE);
        create("rpc", TaskIdleDisposition.PARK_WHEN_IDLE);
        assertThatThrownBy(() -> runtime.freezeTaskData("finite", -clock.get())).hasMessageContaining("fence");
        long terminal = close("finite");
        assertThatThrownBy(() -> runtime.freezeTaskData("finite", terminal - 1)).hasMessageContaining("fence");
        long rpcTerminal = close("rpc");
        assertThatThrownBy(() -> runtime.beginTaskPurge("rpc", rpcTerminal)).hasMessageContaining("finite");
        runtime.beginTaskPurge("finite", terminal);
        runtime.purgeTaskData("finite", terminal);
        runtime.finishTaskPurge("finite", terminal);
        assertThat(scores.getScoreStates(List.of("rpc")).get("rpc")).isNotNull();
    }
}
