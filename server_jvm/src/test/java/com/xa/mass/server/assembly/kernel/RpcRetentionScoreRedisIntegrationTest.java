package com.xa.mass.server.assembly.kernel;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.*;

import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.redis.TaskRedisKeys;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.redis.RedisTaskItemScoreBandCore;
import com.xa.mass.kernel.score.redis.RedisTaskScoreBandCore;
import com.xa.mass.kernel.task.TaskRuntime.*;
import com.xa.mass.kernel.task.redis.RedisTaskRuntime;
import com.xa.mass.server.testsupport.RedisTestScope;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;

@Tag("redis-owner")
class RpcRetentionScoreRedisIntegrationTest {
    private RedisTestScope scope;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisTaskScoreBandCore tasks;
    private RedisTaskItemScoreBandCore items;
    private RedisTaskRuntime runtime;
    private final AtomicLong clock = new AtomicLong(2_000_000_000_000L);

    @BeforeEach void start() {
        scope = RedisTestScope.create("rpc_retention_score");
        client = RedisClient.create(REDIS_URL);
        connection = client.connect();
        tasks = new RedisTaskScoreBandCore(client, scope.keyspace(), clock::get);
        items = new RedisTaskItemScoreBandCore(client, scope.keyspace(), clock::get);
        runtime = new RedisTaskRuntime(client, tasks, items, scope.keyspace(), Duration.ofDays(7).toMillis(), clock::get);
    }

    @AfterEach void stop() {
        if (connection != null) scope.cleanup(connection.sync());
        if (runtime != null) runtime.close();
        if (items != null) items.close();
        if (tasks != null) tasks.close();
        if (connection != null) connection.close();
        if (client != null) client.shutdown();
    }

    private void create(String task, TaskIdleDisposition disposition) {
        assertThat(runtime.createTask(new TaskDescriptor(task, "group", disposition,
                Map.of("priority", "1", "maxRetryTimes", "2"), List.of(), null, Map.of())).status())
                .isEqualTo(TaskCreationStatus.CREATED);
    }

    private long park(String id) {
        var held = tasks.initializeScore(id, 1, 3000);
        var initial = tasks.startObservedPreReviewTask(id, held.score(), 1);
        var normal = tasks.promoteObservedInitialTasks(Map.of(id, initial.score())).get(id);
        assertThat(tasks.parkObservedIdleTask(id, normal.score()).status())
                .isEqualTo(TaskScoreBandCore.TaskScoreTransitionStatus.TRANSITIONED);
        var state = tasks.getScoreStates(List.of(id)).get(id);
        assertThat(state.isIdleParked()).isTrue();
        return state.score();
    }

    private TaskItem input(String id) {
        return new TaskItem(id, "event", clock.get(), Map.of("value", id), 5, clock.get() + 60_000,
                new WorkerQuery("workerId", "worker"));
    }

    @Test void parkedPagesProgressAfterDeletingEveryEqualScorePageAndSkipCorruptIds() {
        long parked = park("park-000");
        var expected = new ArrayList<String>();
        for (int i = 0; i < 213; i++) {
            String id = "park-%03d".formatted(i);
            expected.add(id);
            connection.sync().zadd(TaskRedisKeys.scores(scope.keyspace()), parked, id);
        }
        connection.sync().zadd(TaskRedisKeys.scores(scope.keyspace()), parked, "");
        connection.sync().zadd(TaskRedisKeys.scores(scope.keyspace()), parked, " ");
        tasks.initializeScore("pre-review", 1, 3000);
        tasks.initializeScore("terminal", 1, 3000);
        tasks.closeScore("terminal");

        var first = tasks.scanParkedTasks("0", 1);
        assertThat(first.tasks()).isEmpty();
        assertThat(first.scannedCount()).isEqualTo(1);
        assertThat(first.nextCursor()).isNotEqualTo("0");
        String cursor = first.nextCursor();
        var seen = new ArrayList<String>();
        int visited = first.scannedCount();
        do {
            var page = tasks.scanParkedTasks(cursor, 17);
            assertThat(page.scannedCount()).isBetween(0, 17);
            visited += page.scannedCount();
            for (var task : page.tasks()) {
                assertThat(task.isIdleParked()).isTrue();
                seen.add(task.taskId());
                connection.sync().zrem(TaskRedisKeys.scores(scope.keyspace()), task.taskId());
            }
            cursor = page.nextCursor();
        } while (!cursor.equals("0"));
        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(visited).isEqualTo(215);
        assertThat(tasks.getScoreStates(List.of("pre-review")).get("pre-review").isIdleParked()).isFalse();
        assertThat(tasks.getScoreStates(List.of("terminal")).get("terminal").isIdleParked()).isFalse();
    }

    @Test void eachTerminalTagUsesItsOwnInclusiveHundredMillisecondWindowWithoutWrites() {
        for (int tag = 2; tag <= 9; tag++) {
            String task = "tag-" + tag;
            create(task, TaskIdleDisposition.CLOSE_WHEN_IDLE);
            items.initializeItemScores(task, Map.of("zero", 0L, "edge", 0L, "same-slot", 0L,
                    "after", 0L, "active", 0L), 2);
            items.promoteItemOutcomes(task, Map.of("zero", new TaskItemScoreBandCore.TaskItemOutcomeTarget(tag, 0),
                    "edge", new TaskItemScoreBandCore.TaskItemOutcomeTarget(tag, 1000),
                    "same-slot", new TaskItemScoreBandCore.TaskItemOutcomeTarget(tag, 1099),
                    "after", new TaskItemScoreBandCore.TaskItemOutcomeTarget(tag, 1100)));
            var before = items.getItemScoreStates(task, List.of("zero", "edge", "same-slot", "after", "active"));
            var page = items.scanTerminalItems(task, tag, 1000, "0", 1000);
            assertThat(page.observedScores().keySet()).containsExactly("zero", "edge", "same-slot");
            assertThat(page.scannedCount()).isEqualTo(3);
            assertThat(page.nextCursor()).isEqualTo("0");
            assertThat(items.scanTerminalItems(task, tag, 1099, "0", 1000).observedScores()).isEqualTo(page.observedScores());
            assertThat(items.scanTerminalItems(task, tag, 1100, "0", 1000).observedScores().keySet())
                    .containsExactly("zero", "edge", "same-slot", "after");
            assertThat(items.scanTerminalItems(task, tag == 9 ? 2 : tag + 1, 1100, "0", 1000).observedScores()).isEmpty();
            assertThat(items.getItemScoreStates(task, List.of("zero", "edge", "same-slot", "after", "active"))).isEqualTo(before);
            assertThatThrownBy(() -> page.observedScores().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test void terminalPagesCountCorruptRowsAndResumeAfterDeletingTheCursorMember() {
        create("same", TaskIdleDisposition.CLOSE_WHEN_IDLE);
        var due = new LinkedHashMap<String, Long>();
        var outcomes = new LinkedHashMap<String, TaskItemScoreBandCore.TaskItemOutcomeTarget>();
        var expected = new ArrayList<String>();
        for (int i = 0; i < 213; i++) {
            String id = "item-%03d".formatted(i);
            expected.add(id);
            due.put(id, 0L);
            outcomes.put(id, new TaskItemScoreBandCore.TaskItemOutcomeTarget(6, 3000));
        }
        items.initializeItemScores("same", due, 2);
        items.promoteItemOutcomes("same", outcomes);
        long observed = items.getItemScoreStates("same", List.of("item-000")).get("item-000").score();
        String key = TaskRedisKeys.itemScores(scope.keyspace(), "same");
        connection.sync().zadd(key, observed, "");
        connection.sync().zadd(key, observed + .5, "corrupt-fraction");
        connection.sync().zadd(key, observed + 1, "corrupt-suffix");
        var first = items.scanTerminalItems("same", 6, 3000, "0", 1);
        assertThat(first.observedScores()).isEmpty();
        assertThat(first.scannedCount()).isEqualTo(1);
        String cursor = first.nextCursor();
        assertThat(cursor).isNotEqualTo("0");
        int visited = first.scannedCount();
        var seen = new ArrayList<String>();
        do {
            var page = items.scanTerminalItems("same", 6, 3000, cursor, 17);
            assertThat(page.scannedCount()).isBetween(0, 17);
            visited += page.scannedCount();
            seen.addAll(page.observedScores().keySet());
            for (String id : page.observedScores().keySet()) connection.sync().zrem(key, id);
            cursor = page.nextCursor();
        } while (!cursor.equals("0"));
        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(visited).isEqualTo(216);
    }

    @Test void aHigherTagWithOlderTimeIsEligibleInThatTagsWindow() {
        create("old-time", TaskIdleDisposition.CLOSE_WHEN_IDLE);
        items.initializeItemScores("old-time", Map.of("item", 0L), 2);
        items.promoteItemOutcomes("old-time", Map.of("item", new TaskItemScoreBandCore.TaskItemOutcomeTarget(6, 9000)));
        assertThat(items.scanTerminalItems("old-time", 6, 1000, "0", 10).observedScores()).isEmpty();
        assertThat(items.promoteItemOutcomes("old-time", Map.of("item", new TaskItemScoreBandCore.TaskItemOutcomeTarget(9, 100)))
                .get("item").status()).isEqualTo(TaskItemScoreBandCore.TaskItemScoreTransitionStatus.TRANSITIONED);
        assertThat(items.scanTerminalItems("old-time", 6, 9000, "0", 10).observedScores()).isEmpty();
        assertThat(items.scanTerminalItems("old-time", 9, 100, "0", 10).observedScores()).containsOnlyKeys("item");
    }

    @Test void rpcInitializationNeedsInputOnlyWhenItWouldCreateANewScore() {
        create("rpc", TaskIdleDisposition.PARK_WHEN_IDLE);
        assertThat(items.initializeItemScores("rpc", Map.of("missing", 0L), 2).get("missing").status())
                .isEqualTo(TaskItemScoreBandCore.TaskItemScoreTransitionStatus.NOT_FOUND);
        runtime.appendItems("rpc", List.of(input("retained")));
        var before = items.getItemScoreStates("rpc", List.of("retained")).get("retained");
        assertThat(before).isNotNull();
        connection.sync().hdel(TaskRedisKeys.items(scope.keyspace(), "rpc"), "retained");
        assertThat(items.initializeItemScores("rpc", Map.of("retained", 1000L), 0).get("retained").status())
                .isEqualTo(TaskItemScoreBandCore.TaskItemScoreTransitionStatus.NOOP);
        assertThat(items.getItemScoreStates("rpc", List.of("retained")).get("retained")).isEqualTo(before);
        connection.sync().zrem(TaskRedisKeys.itemScores(scope.keyspace(), "rpc"), "retained");
        assertThat(items.initializeItemScores("rpc", Map.of("retained", 1000L), 0).get("retained").status())
                .isEqualTo(TaskItemScoreBandCore.TaskItemScoreTransitionStatus.NOT_FOUND);
        assertThat(items.getItemScoreStates("rpc", List.of("retained")).get("retained")).isNull();
        create("finite", TaskIdleDisposition.CLOSE_WHEN_IDLE);
        assertThat(items.initializeItemScores("finite", Map.of("without-input", 0L), 2).get("without-input").status())
                .isEqualTo(TaskItemScoreBandCore.TaskItemScoreTransitionStatus.TRANSITIONED);
    }

    @Test void queryBoundsAndCursorWindowsAreValidated() {
        for (int limit : new int[]{0, 1001}) {
            assertThatThrownBy(() -> tasks.scanParkedTasks("0", limit)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> items.scanTerminalItems("task", 6, 1000, "0", limit)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> tasks.scanParkedTasks("invalid!", 10)).isInstanceOf(IllegalArgumentException.class);
        for (int tag : new int[]{1, 10})
            assertThatThrownBy(() -> items.scanTerminalItems("task", tag, 1000, "0", 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> items.scanTerminalItems("task", 6, -1, "0", 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> items.scanTerminalItems("task", 6, TaskItemScoreBandCore.MAX_TIME_MILLIS + 1, "0", 10))
                .isInstanceOf(IllegalArgumentException.class);
        create("cursor", TaskIdleDisposition.CLOSE_WHEN_IDLE);
        items.initializeItemScores("cursor", Map.of("item", 0L), 2);
        items.promoteItemOutcomes("cursor", Map.of("item", new TaskItemScoreBandCore.TaskItemOutcomeTarget(6, 1000)));
        String cursor = items.scanTerminalItems("cursor", 6, 1000, "0", 1).nextCursor();
        assertThatThrownBy(() -> items.scanTerminalItems("cursor", 7, 1000, cursor, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
