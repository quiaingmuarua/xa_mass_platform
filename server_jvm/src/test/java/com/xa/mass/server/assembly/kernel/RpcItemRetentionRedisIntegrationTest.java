package com.xa.mass.server.assembly.kernel;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.*;

import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.redis.TaskRedisKeys;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemOutcomeTarget;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreState;
import com.xa.mass.kernel.score.redis.RedisTaskItemScoreBandCore;
import com.xa.mass.kernel.score.redis.RedisTaskScoreBandCore;
import com.xa.mass.kernel.task.DefaultTaskItemResultEvents;
import com.xa.mass.kernel.task.TaskItemResultEvents.TaskItemOutcomeObservation;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.*;
import com.xa.mass.kernel.task.redis.RedisTaskRuntime;
import com.xa.mass.server.testsupport.RedisTestScope;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;

@Tag("redis-owner")
class RpcItemRetentionRedisIntegrationTest {
    private RedisTestScope scope;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisTaskScoreBandCore taskScores;
    private RedisTaskItemScoreBandCore itemScores;
    private RedisTaskRuntime runtime;
    private final AtomicLong clock = new AtomicLong(2_000_000_000_000L);

    @BeforeEach void start() {
        scope = RedisTestScope.create("rpc_item_retention");
        client = RedisClient.create(REDIS_URL);
        connection = client.connect();
        taskScores = new RedisTaskScoreBandCore(client, scope.keyspace(), clock::get);
        itemScores = new RedisTaskItemScoreBandCore(client, scope.keyspace(), clock::get);
        runtime = new RedisTaskRuntime(client, taskScores, itemScores, scope.keyspace(), 60_000, clock::get);
        create("rpc", TaskIdleDisposition.PARK_WHEN_IDLE);
    }

    @AfterEach void stop() {
        if (connection != null) scope.cleanup(connection.sync());
        if (runtime != null) runtime.close();
        if (itemScores != null) itemScores.close();
        if (taskScores != null) taskScores.close();
        if (connection != null) connection.close();
        if (client != null) client.shutdown();
    }

    @Test void inputReleaseAndTerminalPurgeHaveSeparateCountsWithoutChangingTaskOrOtherIdentities() {
        long selected = terminal("rpc", "selected", 6);
        terminal("rpc", "keeper", 6);
        write("rpc", "selected", 6, "selected-result");
        write("rpc", "keeper", 6, "keeper-result");
        var descriptor = connection.sync().hgetall(descriptor("rpc"));
        var taskState = taskScores.getScoreStates(List.of("rpc"));
        var otherScore = itemScores.getItemScoreStates("rpc", List.of("keeper"));
        connection.sync().pexpire(results("rpc"), 10_000);

        assertThat(runtime.reclaimRpcItems("rpc", Map.of("selected", selected), RpcItemRetentionStage.RELEASE_INPUTS))
                .isEqualTo(new RpcItemRetentionResult(1, 1, 1, 0, 0));
        assertThat(runtime.loadTaskItems("rpc", List.of("selected")).get("selected")).isNull();
        assertThat(runtime.loadTaskItemResults("rpc", List.of("selected")).get("selected").opaqueResultPayload()).isEqualTo("selected-result");
        assertThat(itemScores.getItemScoreStates("rpc", List.of("selected")).get("selected").score()).isEqualTo(selected);
        assertThat(runtime.reclaimRpcItems("rpc", Map.of("selected", selected), RpcItemRetentionStage.RELEASE_INPUTS))
                .isEqualTo(new RpcItemRetentionResult(1, 1, 0, 0, 0));
        assertThat(connection.sync().pttl(results("rpc"))).isBetween(1L, 10_000L);

        assertThat(runtime.reclaimRpcItems("rpc", Map.of("selected", selected), RpcItemRetentionStage.PURGE_TERMINAL))
                .isEqualTo(new RpcItemRetentionResult(1, 1, 0, 1, 1));
        assertThat(runtime.reclaimRpcItems("rpc", Map.of("selected", selected), RpcItemRetentionStage.PURGE_TERMINAL))
                .isEqualTo(new RpcItemRetentionResult(1, 0, 0, 0, 0));
        assertThat(runtime.loadTaskItemResults("rpc", List.of("selected")).get("selected")).isNull();
        assertThat(itemScores.getItemScoreStates("rpc", List.of("selected")).get("selected")).isNull();
        assertThat(runtime.loadTaskItems("rpc", List.of("keeper")).get("keeper")).isNotNull();
        assertThat(runtime.loadTaskItemResults("rpc", List.of("keeper")).get("keeper").opaqueResultPayload()).isEqualTo("keeper-result");
        assertThat(itemScores.getItemScoreStates("rpc", List.of("keeper"))).isEqualTo(otherScore);
        assertThat(connection.sync().pttl(results("rpc"))).isBetween(1L, 10_000L);
        assertThat(connection.sync().hgetall(descriptor("rpc"))).isEqualTo(descriptor);
        assertThat(taskScores.getScoreStates(List.of("rpc"))).isEqualTo(taskState);
        seed("rpc", "new-business-call");
        assertThat(itemScores.getItemScoreStates("rpc", List.of("new-business-call")).get("new-business-call").band())
                .isEqualTo(TaskItemScoreBandCore.TaskItemScoreBand.ACTIVE);
    }

    @Test void onlyExactTerminalScoresQualifyAndTheScoreOwnerIsReadOnce() {
        long terminal = terminal("rpc", "terminal", 6);
        seed("rpc", "active");
        long active = itemScores.getItemScoreStates("rpc", List.of("active")).get("active").score();
        long changed = terminal("rpc", "changed", 6);
        itemScores.promoteItemOutcomes("rpc", Map.of("changed", new TaskItemOutcomeTarget(9, clock.get())));
        var reads = new AtomicInteger();
        try (var guarded = runtimeWithScores(afterScoreRead(() -> reads.incrementAndGet()))) {
            var observed = new LinkedHashMap<String, Long>();
            observed.put("terminal", terminal);
            observed.put("active", active);
            observed.put("changed", changed);
            observed.put("missing", terminal);
            assertThat(guarded.reclaimRpcItems("rpc", observed, RpcItemRetentionStage.PURGE_TERMINAL))
                    .isEqualTo(new RpcItemRetentionResult(4, 1, 1, 0, 1));
            assertThat(reads.get()).isEqualTo(1);
        }
        assertThat(runtime.loadTaskItems("rpc", List.of("active", "changed")).values()).allMatch(Objects::nonNull);
    }

    @Test void aNewerTerminalOutcomeBetweenQualificationAndDeletionKeepsItsInputResultAndScore() {
        long observed = terminal("rpc", "item", 6);
        write("rpc", "item", 6, "kept");
        try (var guarded = runtimeWithScores(afterScoreRead(() -> itemScores.promoteItemOutcomes("rpc",
                Map.of("item", new TaskItemOutcomeTarget(9, clock.get() + 100)))))) {
            assertThat(guarded.reclaimRpcItems("rpc", Map.of("item", observed), RpcItemRetentionStage.PURGE_TERMINAL))
                    .isEqualTo(new RpcItemRetentionResult(1, 0, 0, 0, 0));
        }
        assertThat(runtime.loadTaskItems("rpc", List.of("item")).get("item")).isNotNull();
        assertThat(runtime.loadTaskItemResults("rpc", List.of("item")).get("item").opaqueResultPayload()).isEqualTo("kept");
        assertThat(itemScores.getItemScoreStates("rpc", List.of("item")).get("item").tag()).isEqualTo(9);
    }

    @Test void finiteMissingAndFrozenTasksNeverQualifyAndThePhaseIsCheckedAtDeletion() {
        create("finite", TaskIdleDisposition.CLOSE_WHEN_IDLE);
        long finite = terminal("finite", "item", 6);
        assertThat(runtime.reclaimRpcItems("finite", Map.of("item", finite), RpcItemRetentionStage.PURGE_TERMINAL).matchedItems()).isZero();
        assertThat(runtime.loadTaskItems("finite", List.of("item")).get("item")).isNotNull();
        assertThat(runtime.reclaimRpcItems("missing", Map.of("item", finite), RpcItemRetentionStage.RELEASE_INPUTS).matchedItems()).isZero();
        long observed = terminal("rpc", "item", 6);
        try (var guarded = runtimeWithScores(afterScoreRead(() -> connection.sync().hset(descriptor("rpc"), "dataPhase", "FROZEN")))) {
            assertThat(guarded.reclaimRpcItems("rpc", Map.of("item", observed), RpcItemRetentionStage.PURGE_TERMINAL).matchedItems()).isZero();
        }
        assertThat(runtime.loadTaskItems("rpc", List.of("item")).get("item")).isNotNull();
    }

    @Test void allResourceTypesAreCheckedBeforeAnyBatchDeletion() {
        for (int index = 0; index < 4; index++) {
            String task = "wrong-type-" + index;
            create(task, TaskIdleDisposition.PARK_WHEN_IDLE);
            long observed = terminal(task, "one", 6);
            terminal(task, "two", 6);
            write(task, "one", 6, "one-result");
            write(task, "two", 6, "two-result");
            var keys = List.of(descriptor(task), inputs(task), results(task), scores(task));
            String wrong = keys.get(index);
            connection.sync().unlink(wrong);
            connection.sync().set(wrong, "wrong-type");
            var before = new LinkedHashMap<String, byte[]>();
            keys.forEach(key -> before.put(key, connection.sync().dump(key)));
            assertThatThrownBy(() -> runtime.reclaimRpcItems(task, Map.of("one", observed, "two", observed), RpcItemRetentionStage.PURGE_TERMINAL))
                    .isInstanceOf(RuntimeException.class);
            before.forEach((key, bytes) -> assertThat(connection.sync().dump(key)).isEqualTo(bytes));
        }
    }

    @Test void inputlessRetainedScoresStillAcceptFailureOrdinarySuccessAndLaterObservation() {
        long observed = terminal("rpc", "item", 6);
        runtime.reclaimRpcItems("rpc", Map.of("item", observed), RpcItemRetentionStage.RELEASE_INPUTS);
        runtime.storeTaskItemFailedResults("rpc", List.of("item"));
        assertThat(runtime.loadTaskItemResults("rpc", List.of("item")).get("item").succeeded()).isFalse();
        var events = new DefaultTaskItemResultEvents(runtime, itemScores, 6);
        events.onItemsSucceeded("rpc", Map.of("item", "ordinary"), clock.get());
        assertThat(runtime.loadTaskItemResults("rpc", List.of("item")).get("item").opaqueResultPayload()).isEqualTo("ordinary");
        events.onItemOutcomesObserved("rpc", List.of(new TaskItemOutcomeObservation("item", 9, clock.get() + 100, "observation")));
        assertThat(runtime.loadTaskItemResults("rpc", List.of("item")).get("item").opaqueResultPayload()).isEqualTo("observation");
        assertThat(runtime.loadTaskItems("rpc", List.of("item")).get("item")).isNull();
    }

    @Test void purgedAndNeverSubmittedRpcIdentitiesCannotCreateOrRenewResultsFromLateEvidence() {
        long observed = terminal("rpc", "item", 6);
        write("rpc", "item", 6, "before");
        runtime.reclaimRpcItems("rpc", Map.of("item", observed), RpcItemRetentionStage.PURGE_TERMINAL);
        var events = new DefaultTaskItemResultEvents(runtime, itemScores, 6);
        events.onItemsSucceeded("rpc", Map.of("item", "late-ordinary"), clock.get());
        events.onItemOutcomesObserved("rpc", List.of(new TaskItemOutcomeObservation("item", 9, clock.get(), "late-observation")));
        runtime.storeTaskItemSuccessResults("rpc", Map.of("never-submitted", new TaskItemSuccessResult(6, clock.get(), "unknown")));
        runtime.storeTaskItemFailedResults("rpc", List.of("item", "never-submitted"));
        assertThat(connection.sync().exists(inputs("rpc"), results("rpc"), scores("rpc"))).isZero();
        assertThat(taskScores.getScoreStates(List.of("rpc")).get("rpc")).isNotNull();

        seed("rpc", "keeper");
        write("rpc", "keeper", 6, "kept");
        connection.sync().pexpire(results("rpc"), 10_000);
        runtime.storeTaskItemSuccessResults("rpc", Map.of("item", new TaskItemSuccessResult(9, clock.get() + 100, "late")));
        runtime.storeTaskItemFailedResults("rpc", List.of("item"));
        assertThat(connection.sync().hlen(results("rpc"))).isEqualTo(1);
        assertThat(connection.sync().pttl(results("rpc"))).isBetween(1L, 10_000L);
    }

    @Test void observationPromotedBeforePurgeCannotRecreateTheResultAfterPurge() {
        terminal("rpc", "item", 6);
        var intercepted = (TaskRuntime) Proxy.newProxyInstance(TaskRuntime.class.getClassLoader(), new Class<?>[]{TaskRuntime.class},
                (_p, method, args) -> {
                    if (method.getName().equals("storeTaskItemSuccessResults")) {
                        long current = itemScores.getItemScoreStates("rpc", List.of("item")).get("item").score();
                        runtime.reclaimRpcItems("rpc", Map.of("item", current), RpcItemRetentionStage.PURGE_TERMINAL);
                    }
                    return invoke(method, runtime, args);
                });
        new DefaultTaskItemResultEvents(intercepted, itemScores, 6).onItemOutcomesObserved("rpc",
                List.of(new TaskItemOutcomeObservation("item", 9, clock.get(), "observation")));
        assertThat(connection.sync().exists(inputs("rpc"), results("rpc"), scores("rpc"))).isZero();
    }

    @Test void appendPurgedAfterInputStorageButBeforeInitializationCannotCreateAnOrphanScore() {
        long observed = terminal("rpc", "item", 6);
        write("rpc", "item", 6, "before-purge");
        runtime.reclaimRpcItems("rpc", Map.of("item", observed), RpcItemRetentionStage.RELEASE_INPUTS);
        assertThat(runtime.loadTaskItems("rpc", List.of("item")).get("item")).isNull();
        var initializations = new AtomicInteger();
        var intercepted = (TaskItemScoreBandCore) Proxy.newProxyInstance(TaskItemScoreBandCore.class.getClassLoader(),
                new Class<?>[]{TaskItemScoreBandCore.class}, (_p, method, args) -> {
                    if (method.getName().equals("initializeItemScores")) {
                        initializations.incrementAndGet();
                        assertThat(runtime.loadTaskItems("rpc", List.of("item")).get("item")).isNotNull();
                        assertThat(runtime.reclaimRpcItems("rpc", Map.of("item", observed), RpcItemRetentionStage.PURGE_TERMINAL))
                                .isEqualTo(new RpcItemRetentionResult(1, 1, 1, 1, 1));
                    }
                    return invoke(method, itemScores, args);
                });
        try (var guarded = runtimeWithScores(intercepted)) {
            assertThat(guarded.appendItems("rpc", List.of(inputForAppend("item"))).get("item").status())
                    .isEqualTo(TaskItemAppendStatus.RETRYABLE);
        }
        assertThat(initializations.get()).isEqualTo(1);
        assertThat(connection.sync().exists(inputs("rpc"), results("rpc"), scores("rpc"))).isZero();
    }

    @Test void mixedResultBatchesKeepLiveMembersWithoutRecreatingPurgedOrUnknownMembers() {
        long observed = terminal("rpc", "purged", 6);
        runtime.reclaimRpcItems("rpc", Map.of("purged", observed), RpcItemRetentionStage.PURGE_TERMINAL);
        seed("rpc", "success-live");
        seed("rpc", "failure-live");
        var before = itemScores.getItemScoreStates("rpc", List.of("success-live", "failure-live"));
        var mixed = new LinkedHashMap<String, TaskItemSuccessResult>();
        mixed.put("purged", new TaskItemSuccessResult(6, clock.get(), "discard-purged"));
        mixed.put("success-live", new TaskItemSuccessResult(6, clock.get(), "kept-success"));
        mixed.put("never-submitted", new TaskItemSuccessResult(6, clock.get(), "discard-unknown"));
        runtime.storeTaskItemSuccessResults("rpc", mixed);
        var success = runtime.loadTaskItemResults("rpc", List.of("purged", "success-live", "never-submitted"));
        assertThat(success.get("purged")).isNull();
        assertThat(success.get("never-submitted")).isNull();
        assertThat(success.get("success-live").opaqueResultPayload()).isEqualTo("kept-success");
        assertThat(connection.sync().hlen(results("rpc"))).isEqualTo(1);

        connection.sync().pexpire(results("rpc"), 10_000);
        runtime.storeTaskItemFailedResults("rpc", List.of("purged", "failure-live", "never-submitted", "success-live"));
        var failed = runtime.loadTaskItemResults("rpc", List.of("purged", "success-live", "failure-live", "never-submitted"));
        assertThat(failed.get("purged")).isNull();
        assertThat(failed.get("never-submitted")).isNull();
        assertThat(failed.get("success-live").opaqueResultPayload()).isEqualTo("kept-success");
        assertThat(failed.get("failure-live").succeeded()).isFalse();
        assertThat(connection.sync().hlen(results("rpc"))).isEqualTo(2);
        assertThat(connection.sync().pttl(results("rpc"))).isBetween(50_000L, 60_000L);
        assertThat(itemScores.getItemScoreStates("rpc", List.of("success-live", "failure-live"))).isEqualTo(before);

        connection.sync().pexpire(results("rpc"), 10_000);
        runtime.storeTaskItemSuccessResults("rpc", Map.of("purged", new TaskItemSuccessResult(9, clock.get(), "late")));
        runtime.storeTaskItemFailedResults("rpc", List.of("purged", "never-submitted"));
        assertThat(connection.sync().pttl(results("rpc"))).isBetween(1L, 10_000L);
        assertThat(connection.sync().hlen(results("rpc"))).isEqualTo(2);
    }

    @Test void ordinarySuccessStoredBeforePurgeCannotRecreateTheItemScoreAfterPurge() {
        long observed = terminal("rpc", "item", 6);
        var intercepted = (TaskRuntime) Proxy.newProxyInstance(TaskRuntime.class.getClassLoader(), new Class<?>[]{TaskRuntime.class},
                (_p, method, args) -> {
                    Object result = invoke(method, runtime, args);
                    if (method.getName().equals("storeTaskItemSuccessResults"))
                        runtime.reclaimRpcItems("rpc", Map.of("item", observed), RpcItemRetentionStage.PURGE_TERMINAL);
                    return result;
                });
        new DefaultTaskItemResultEvents(intercepted, itemScores, 6).onItemsSucceeded("rpc", Map.of("item", "ordinary"), clock.get());
        assertThat(connection.sync().exists(inputs("rpc"), results("rpc"), scores("rpc"))).isZero();
    }

    @Test void finiteResultWritesKeepTheirExistingMissingItemBehavior() {
        create("finite", TaskIdleDisposition.CLOSE_WHEN_IDLE);
        runtime.storeTaskItemSuccessResults("finite", Map.of("success", new TaskItemSuccessResult(6, clock.get(), "finite")));
        runtime.storeTaskItemFailedResults("finite", List.of("failure"));
        assertThat(runtime.loadTaskItemResults("finite", List.of("success", "failure")).values()).allMatch(Objects::nonNull);
        assertThat(connection.sync().pttl(results("finite"))).isEqualTo(-1);
        assertThat(connection.sync().exists(scores("finite"))).isZero();
    }

    private void create(String task, TaskIdleDisposition mode) {
        assertThat(runtime.createTask(new TaskDescriptor(task, "group", mode,
                Map.of("priority", "1", "maxRetryTimes", "2"), List.of(), null,
                Map.of("server.inputOwner", "raw", "server.projectId", "default", "server.createdAtMillis", Long.toString(clock.get())))).status())
                .isEqualTo(TaskCreationStatus.CREATED);
    }

    private void seed(String task, String id) {
        assertThat(runtime.appendItems(task, List.of(inputForAppend(id))).get(id).status()).isEqualTo(TaskItemAppendStatus.APPENDED);
    }

    private TaskItem inputForAppend(String id) {
        return new TaskItem(id, "event", clock.get(), Map.of("value", id), 5,
                clock.get() + 60_000, new WorkerQuery("workerId", "worker"));
    }

    private long terminal(String task, String id, int tag) {
        seed(task, id);
        itemScores.promoteItemOutcomes(task, Map.of(id, new TaskItemOutcomeTarget(tag, clock.get() - 1000)));
        return itemScores.getItemScoreStates(task, List.of(id)).get(id).score();
    }

    private void write(String task, String id, int tag, String payload) {
        runtime.storeTaskItemSuccessResults(task, Map.of(id, new TaskItemSuccessResult(tag, clock.get() - 1000, payload)));
    }

    private RedisTaskRuntime runtimeWithScores(TaskItemScoreBandCore scores) {
        return new RedisTaskRuntime(client, taskScores, scores, scope.keyspace(), 60_000, clock::get);
    }

    private TaskItemScoreBandCore afterScoreRead(Runnable action) {
        return (TaskItemScoreBandCore) Proxy.newProxyInstance(TaskItemScoreBandCore.class.getClassLoader(),
                new Class<?>[]{TaskItemScoreBandCore.class}, (_p, method, args) -> {
                    Object result = invoke(method, itemScores, args);
                    if (method.getName().equals("getItemScoreStates")) action.run();
                    return result;
                });
    }

    private static Object invoke(java.lang.reflect.Method method, Object receiver, Object[] arguments) throws Throwable {
        try { return method.invoke(receiver, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private String descriptor(String task) { return TaskRedisKeys.descriptor(scope.keyspace(), task); }
    private String inputs(String task) { return TaskRedisKeys.items(scope.keyspace(), task); }
    private String results(String task) { return TaskRedisKeys.results(scope.keyspace(), task); }
    private String scores(String task) { return TaskRedisKeys.itemScores(scope.keyspace(), task); }
}
