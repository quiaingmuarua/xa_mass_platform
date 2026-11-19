package com.xa.mass.server.task.retention;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.redis.TaskRedisKeys;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemOutcomeTarget;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.score.redis.RedisTaskItemScoreBandCore;
import com.xa.mass.kernel.score.redis.RedisTaskScoreBandCore;
import com.xa.mass.kernel.task.TaskRuntime.*;
import com.xa.mass.kernel.task.redis.RedisTaskResourceCatalog;
import com.xa.mass.kernel.task.redis.RedisTaskRuntime;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.task.credit.TaskCreditStartStore;
import com.xa.mass.server.task.directory.TaskAttribution;
import com.xa.mass.server.task.directory.TaskCreationRegistry;
import com.xa.mass.server.task.directory.TaskDirectory;
import com.xa.mass.server.task.history.TaskHistoryPublisher;
import com.xa.mass.server.task.history.TaskHistoryStore;
import com.xa.mass.server.testing.TaskCallers;
import com.xa.mass.server.testsupport.RedisTestScope;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

@Tag("redis-owner")
class RpcTaskRetentionServiceRedisIntegrationTest {
    private static final long NOW = 2_000_000_000_000L;
    @TempDir Path files;
    private final AtomicLong clock = new AtomicLong(NOW);
    private RedisTestScope scope;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisTaskScoreBandCore scores;
    private RedisTaskItemScoreBandCore items;
    private RedisTaskRuntime runtime;
    private RedisTaskResourceCatalog catalog;
    private TaskHistoryStore historyStore;
    private TaskHistoryPublisher history;

    @BeforeEach void start() throws Exception {
        scope = RedisTestScope.create("rpc_item_retention");
        client = RedisClient.create(REDIS_URL); connection = client.connect();
        scores = new RedisTaskScoreBandCore(client, scope.keyspace(), clock::get);
        items = spy(new RedisTaskItemScoreBandCore(client, scope.keyspace(), clock::get));
        runtime = spy(new RedisTaskRuntime(client, scores, items, scope.keyspace(), Duration.ofDays(7).toMillis(), clock::get));
        catalog = new RedisTaskResourceCatalog(client, scope.keyspace());
        historyStore = new TaskHistoryStore(JsonMapper.builder().build(), properties(true));
        history = new TaskHistoryPublisher(runtime, items, historyStore, scope.keyspace(), properties(true));
    }
    @AfterEach void stop() throws Exception {
        if (historyStore != null) historyStore.close();
        if (connection != null) scope.cleanup(connection.sync());
        if (runtime != null) runtime.close();
        if (catalog != null) catalog.close();
        if (items != null) items.close();
        if (scores != null) scores.close();
        if (connection != null) connection.close();
        if (client != null) client.shutdown();
    }
    private TaskRetentionProperties properties(boolean enabled) {
        return new TaskRetentionProperties(true, Duration.ofHours(1), Duration.ofDays(7), Duration.ofDays(30),
                Duration.ofHours(1), files.resolve("history.sqlite"), enabled, Duration.ofHours(1), Duration.ofHours(24));
    }
    private TaskRetentionService maintenance(boolean enabled) {
        return new TaskRetentionService(runtime, catalog, scores, items, mock(TaskDirectory.class), mock(TaskCreationRegistry.class),
                history, historyStore, scope.keyspace(), new OperationGuard(), properties(enabled), mock(TaskCreditStartStore.class), clock::get);
    }
    private void create(String id, TaskIdleDisposition disposition, Map<String, String> metadata) {
        runtime.createTask(new TaskDescriptor(id, "g", disposition, Map.of("priority", "1", "maxRetryTimes", "1"),
                List.of(), null, metadata));
    }
    private Map<String, String> raw() {
        return new TaskAttribution("p", clock.get(), "1000").stamp(Map.of(TaskCaller.INPUT_OWNER, TaskCaller.RAW_INPUT));
    }
    private void park(String task) {
        var state = scores.getScoreStates(List.of(task)).get(task);
        if (state.band() == TaskScoreBand.PRE_REVIEW) {
            scores.startObservedPreReviewTask(task, state.score(), 1);
            state = scores.getScoreStates(List.of(task)).get(task);
        }
        if (state.isInitial()) {
            scores.promoteObservedInitialTasks(Map.of(task, state.score()));
            state = scores.getScoreStates(List.of(task)).get(task);
        }
        scores.parkObservedIdleTask(task, state.score());
        assertThat(scores.getScoreStates(List.of(task)).get(task).isIdleParked()).isTrue();
    }
    private void append(String task, String id) {
        runtime.appendItems(task, List.of(new TaskItem(id, "event", clock.get(), Map.of("input", id), 1,
                clock.get() + Duration.ofDays(2).toMillis(), new WorkerQuery("workerId", "w"))));
    }
    private void terminal(String task, String id, long ageMillis) {
        append(task, id);
        long time = clock.get() - ageMillis;
        runtime.storeTaskItemSuccessResults(task, Map.of(id, new TaskItemSuccessResult(6, time, "result-" + id)));
        items.promoteItemOutcomes(task, Map.of(id, new TaskItemOutcomeTarget(6, time)));
    }

    @Test void defaultOffPreservesLegacyInputsResultsAndScores() {
        create("rpc", TaskIdleDisposition.PARK_WHEN_IDLE, raw());
        terminal("rpc", "old", Duration.ofHours(25).toMillis()); park("rpc");
        var input = runtime.loadTaskItems("rpc", List.of("old"));
        var result = runtime.loadTaskItemResults("rpc", List.of("old"));
        var score = items.getItemScoreStates("rpc", List.of("old"));
        maintenance(false).runOnce();
        assertThat(runtime.loadTaskItems("rpc", List.of("old"))).isEqualTo(input);
        assertThat(runtime.loadTaskItemResults("rpc", List.of("old"))).isEqualTo(result);
        assertThat(items.getItemScoreStates("rpc", List.of("old"))).isEqualTo(score);
        verify(items, never()).scanTerminalItems(anyString(), anyInt(), anyLong(), anyString(), anyInt());
    }

    @Test void twoStagesKeepActiveAndRecentDataAndPurgeResultsEvenAfterInputWasReleased() {
        create("rpc", TaskIdleDisposition.PARK_WHEN_IDLE, raw());
        terminal("rpc", "old", Duration.ofHours(25).toMillis());
        terminal("rpc", "input", Duration.ofHours(2).toMillis());
        terminal("rpc", "recent", Duration.ofMinutes(5).toMillis());
        append("rpc", "active"); park("rpc");
        var descriptor = catalog.loadTaskAllocationDescriptors(List.of("rpc"));
        var taskScore = scores.getScoreStates(List.of("rpc"));
        var inputScore = items.getItemScoreStates("rpc", List.of("input", "recent", "active"));
        var service = maintenance(true); service.runOnce();
        assertThat(runtime.loadTaskItems("rpc", List.of("old", "input")).values()).containsOnlyNulls();
        assertThat(runtime.loadTaskItemResults("rpc", List.of("old")).get("old")).isNull();
        assertThat(items.getItemScoreStates("rpc", List.of("old")).get("old")).isNull();
        assertThat(runtime.loadTaskItemResults("rpc", List.of("input")).get("input").opaqueResultPayload()).isEqualTo("result-input");
        assertThat(items.getItemScoreStates("rpc", List.of("input", "recent", "active"))).isEqualTo(inputScore);
        assertThat(runtime.loadTaskItems("rpc", List.of("recent", "active")).values()).doesNotContainNull();
        assertThat(catalog.loadTaskAllocationDescriptors(List.of("rpc"))).isEqualTo(descriptor);
        assertThat(scores.getScoreStates(List.of("rpc"))).isEqualTo(taskScore);
        clock.addAndGet(Duration.ofHours(25).toMillis()); service.runOnce();
        assertThat(connection.sync().hlen(TaskRedisKeys.items(scope.keyspace(), "rpc"))).isEqualTo(1);
        assertThat(runtime.loadTaskItems("rpc", List.of("active")).get("active")).isNotNull();
        assertThat(runtime.loadTaskItemResults("rpc", List.of("input", "recent")).values()).containsOnlyNulls();
        assertThat(items.getItemScoreStates("rpc", List.of("input", "recent")).values()).containsOnlyNulls();
        assertThat(items.getItemScoreStates("rpc", List.of("active")).get("active")).isEqualTo(inputScore.get("active"));
    }

    @Test void consumerOwnershipAndServerAttributionExcludeSmsFiniteAndMalformedTasks() {
        var sms = new java.util.LinkedHashMap<>(raw()); sms.put(TaskCaller.INPUT_OWNER, "sms");
        var malformed = new java.util.LinkedHashMap<>(raw()); malformed.put(TaskAttribution.CREATED, "corrupt");
        create("raw", TaskIdleDisposition.PARK_WHEN_IDLE, raw());
        create("sms", TaskIdleDisposition.PARK_WHEN_IDLE, sms);
        create("finite", TaskIdleDisposition.CLOSE_WHEN_IDLE, raw());
        create("unattributed", TaskIdleDisposition.PARK_WHEN_IDLE, Map.of(TaskCaller.INPUT_OWNER, TaskCaller.RAW_INPUT));
        create("malformed", TaskIdleDisposition.PARK_WHEN_IDLE, malformed);
        create("busy", TaskIdleDisposition.PARK_WHEN_IDLE, raw());
        for (String id : List.of("raw", "sms", "finite", "unattributed", "malformed", "busy")) {
            terminal(id, "old", Duration.ofHours(25).toMillis());
            if (!id.equals("busy")) park(id);
        }
        maintenance(true).runOnce();
        assertThat(items.getItemScoreStates("raw", List.of("old")).get("old")).isNull();
        for (String id : List.of("sms", "finite", "unattributed", "malformed", "busy")) {
            assertThat(runtime.loadTaskItems(id, List.of("old")).get("old")).isNotNull();
            assertThat(runtime.loadTaskItemResults(id, List.of("old")).get("old")).isNotNull();
            assertThat(items.getItemScoreStates(id, List.of("old")).get("old")).isNotNull();
        }
    }

    @Test void aTaskThatBecomesActiveBetweenBoundedMutationsIsDeferredUntilItParksAgain() {
        create("rpc", TaskIdleDisposition.PARK_WHEN_IDLE, raw());
        for (int i = 0; i < 151; i++) terminal("rpc", "old-%04d".formatted(i), Duration.ofHours(25).toMillis());
        park("rpc");
        doAnswer(call -> {
            var result = (RpcItemRetentionResult) call.callRealMethod();
            scores.tryReleaseIdlePark("rpc");
            return result;
        }).when(runtime).reclaimRpcItems(eq("rpc"), anyMap(), eq(RpcItemRetentionStage.PURGE_TERMINAL));
        var service = maintenance(true); service.runOnce();
        assertThat(items.observeItemScoreCounts(List.of("rpc")).get("rpc").total()).isEqualTo(51);
        doCallRealMethod().when(runtime).reclaimRpcItems(eq("rpc"), anyMap(), eq(RpcItemRetentionStage.PURGE_TERMINAL));
        park("rpc"); service.runOnce();
        assertThat(items.observeItemScoreCounts(List.of("rpc")).get("rpc").total()).isZero();
        assertThat(connection.sync().exists(TaskRedisKeys.items(scope.keyspace(), "rpc"), TaskRedisKeys.results(scope.keyspace(), "rpc"))).isZero();
    }

    @Test void rpcFailureDoesNotPreventFinitePublicationAndRetriesItsOwnScanOnTheNextPass() {
        clock.addAndGet(-Duration.ofHours(2).toMillis());
        create("finite", TaskIdleDisposition.CLOSE_WHEN_IDLE, raw());
        terminal("finite", "one", 0); scores.closeScore("finite");
        clock.set(NOW);
        create("rpc", TaskIdleDisposition.PARK_WHEN_IDLE, raw());
        terminal("rpc", "old", Duration.ofHours(25).toMillis()); park("rpc");
        doThrow(new IllegalStateException("scan unavailable")).when(items)
                .scanTerminalItems(eq("rpc"), anyInt(), anyLong(), anyString(), anyInt());
        var service = maintenance(true);
        assertThatCode(service::runOnce).doesNotThrowAnyException();
        assertThat(runtime.loadTaskDataStates(List.of("finite")).get("finite").phase()).isEqualTo(TaskDataPhase.RELEASED);
        assertThat(runtime.loadTaskItems("rpc", List.of("old")).get("old")).isNotNull();
        doCallRealMethod().when(items).scanTerminalItems(eq("rpc"), anyInt(), anyLong(), anyString(), anyInt());
        service.runOnce();
        assertThat(items.getItemScoreStates("rpc", List.of("old")).get("old")).isNull();
    }

    @Test void enablingRpcMaintenanceDoesNotExpandTheFiniteOnlyManualOperation() {
        create("rpc", TaskIdleDisposition.PARK_WHEN_IDLE, raw()); park("rpc");
        assertThatThrownBy(() -> maintenance(true).run(TaskCallers.caller("1000", "admin"), "rpc"))
                .hasMessageContaining("Only finite Tasks support retention");
    }

    @Test void corruptionAfterScanCannotPinTheCachedPageOrBlockALaterTaskForever() {
        create("rpc-a", TaskIdleDisposition.PARK_WHEN_IDLE, raw());
        terminal("rpc-a", "broken", Duration.ofHours(25).toMillis());
        terminal("rpc-a", "valid", Duration.ofHours(25).toMillis()); park("rpc-a");
        create("rpc-z", TaskIdleDisposition.PARK_WHEN_IDLE, raw());
        terminal("rpc-z", "later", Duration.ofHours(25).toMillis()); park("rpc-z");
        var once = new AtomicBoolean(true);
        doAnswer(call -> {
            var page = call.callRealMethod();
            if (once.getAndSet(false)) connection.sync().zadd(TaskRedisKeys.itemScores(scope.keyspace(), "rpc-a"), 1, "broken");
            return page;
        }).when(items).scanTerminalItems(eq("rpc-a"), eq(6), anyLong(), anyString(), anyInt());
        var service = maintenance(true);
        service.runOnce();
        assertThat(connection.sync().zcard(TaskRedisKeys.itemScores(scope.keyspace(), "rpc-a"))).isEqualTo(2);
        assertThat(items.getItemScoreStates("rpc-z", List.of("later")).get("later")).isNotNull();
        service.runOnce();
        assertThat(connection.sync().zscore(TaskRedisKeys.itemScores(scope.keyspace(), "rpc-a"), "broken")).isEqualTo(1d);
        assertThat(items.getItemScoreStates("rpc-a", List.of("valid")).get("valid")).isNull();
        assertThat(items.getItemScoreStates("rpc-z", List.of("later")).get("later")).isNull();
        assertThat(connection.sync().hexists(TaskRedisKeys.items(scope.keyspace(), "rpc-a"), "broken")).isTrue();
        assertThat(connection.sync().hexists(TaskRedisKeys.results(scope.keyspace(), "rpc-a"), "broken")).isTrue();
    }
}
