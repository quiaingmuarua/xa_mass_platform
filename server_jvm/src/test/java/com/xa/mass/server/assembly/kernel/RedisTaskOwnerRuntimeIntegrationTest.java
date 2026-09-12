package com.xa.mass.server.assembly.kernel;

import com.xa.mass.kernel.task.TaskItemWorkerSelector;


import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.TaskItemMapper;
import com.xa.mass.server.task.TaskItemOutcomeProperties;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreTransitionStatus.*;

import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.score.redis.RedisTaskScoreBandCore;
import com.xa.mass.kernel.score.redis.RedisTaskItemScoreBandCore;
import com.xa.mass.kernel.task.DefaultTaskCallItemSubmission;
import com.xa.mass.kernel.task.DefaultTaskLifecycleCommands;
import com.xa.mass.kernel.task.TaskCallItemSubmission;
import com.xa.mass.kernel.task.TaskLifecycleCommands;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemSuccessResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemResult;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import com.xa.mass.kernel.task.redis.RedisTaskResourceCatalog;
import com.xa.mass.kernel.task.redis.RedisTaskRuntime;
import com.xa.mass.server.api.v1.controller.TaskControlController;
import com.xa.mass.server.task.TaskCreationService;
import com.xa.mass.server.task.TaskLifecycleService;
import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.workerdelivery.json.Jsons;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;
import io.lettuce.core.event.command.CommandListener;
import io.lettuce.core.event.command.CommandStartedEvent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("redis-owner")
class RedisTaskOwnerRuntimeIntegrationTest {

    private RedisTestScope testScope;
    private RedisKeyspace keyspace;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> redis;
    private RedisTaskScoreBandCore scoreCore;
    private RedisTaskItemScoreBandCore itemScoreCore;
    private RedisTaskRuntime runtime;
    private RedisTaskResourceCatalog catalog;
    private TaskLifecycleCommands lifecycle;
    private TaskCallItemSubmission callSubmission;

    @BeforeEach
    void setUp() {
        testScope = RedisTestScope.create("java_task_owner");
        keyspace = testScope.keyspace();
        redisClient = RedisClient.create(REDIS_URL);
        connection = redisClient.connect(StringCodec.UTF8);
        redis = connection.sync();
        scoreCore = new RedisTaskScoreBandCore(redisClient, keyspace);
        itemScoreCore = new RedisTaskItemScoreBandCore(
                redisClient,
                keyspace
        );
        runtime = new RedisTaskRuntime(
                redisClient,
                scoreCore,
                itemScoreCore,
                keyspace
        );
        catalog = new RedisTaskResourceCatalog(redisClient, keyspace);
        lifecycle = new DefaultTaskLifecycleCommands(scoreCore, catalog);
        callSubmission = new DefaultTaskCallItemSubmission(
                scoreCore,
                runtime
        );
    }

    @AfterEach
    void tearDown() {
        if (redis != null) {
            testScope.cleanup(redis);
        }
        if (runtime != null) {
            runtime.close();
        }
        if (scoreCore != null) {
            scoreCore.close();
        }
        if (itemScoreCore != null) {
            itemScoreCore.close();
        }
        if (catalog != null) {
            catalog.close();
        }
        if (connection != null) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void appendAndSuccessResultMatchTaskOwnerShape() {
        long createdAt = redisTimeMillis();
        storeTask("task-1", "PRECOMPUTED_TASK_RULE");
        TaskItem item = new TaskItem(
                "message-1",
                "telecom.phone.inspect",
                createdAt,
                Map.of("phoneNumber", "+14155552671"),
                0,
                createdAt + 60_000,
                TaskItemWorkerSelector.parse(Map.of())
        );

        assertThat(runtime.appendItems(
                "task-1",
                List.of(item)
        ).get("message-1").status()).isEqualTo(
                TaskItemAppendStatus.APPENDED
        );
        assertThat(redis.hget(
                keyspace.base() + ":task:task-1:items",
                "message-1"
        )).isEqualTo(
                "{\"createdAtMillis\":" + createdAt + ","
                        + "\"eventCode\":\"telecom.phone.inspect\","
                        + "\"expireAtMillis\":" + (createdAt + 60_000) + ","
                        + "\"payload\":{\"phoneNumber\":\"+14155552671\"},"
                        + "\"priority\":0,\"workerSelector\":{}}"
        );
        double score = redis.zscore(
                keyspace.base() + ":task:task-1:item_score",
                "message-1"
        );
        long expected = TaskItemScoreBandCore.ACTIVE_TAG
                * TaskItemScoreBandCore.TAG_FACTOR
                + (createdAt / TaskItemScoreBandCore.SLOT_MILLIS)
                * TaskItemScoreBandCore.SUFFIX_FACTOR
                + 4;
        assertThat((long) score).isEqualTo(expected);

        runtime.storeTaskItemSuccessResults("task-1", successResults(Map.of("message-1", "{\"valid\":true}"), 1000));
        assertThat(redis.hget(
                keyspace.base() + ":task:task-1:results",
                "message-1"
        )).isEqualTo(
                "{\"code\":\"200\",\"observedAtMillis\":1000,"
                        + "\"opaqueResultPayload\":"
                        + "\"{\\\"valid\\\":true}\",\"tag\":6}"
        );
        assertThat(redis.exists(
                keyspace.base() + ":task:task-1:results:success"
        )).isZero();
        assertThat(itemScoreCore.promoteItemOutcomes("task-1", outcomeTargets(List.of("message-1"), 6, redisTimeMillis())).get("message-1").status()).isEqualTo(
                TaskItemScoreBandCore.TaskItemScoreTransitionStatus
                        .TRANSITIONED
        );
        long finalScore = redis.zscore(
                keyspace.base() + ":task:task-1:item_score",
                "message-1"
        ).longValue();
        assertThat(finalScore / TaskItemScoreBandCore.TAG_FACTOR)
                .isEqualTo(6);
        var loaded = runtime.loadTaskItemResults(
                "task-1",
                List.of("message-1", "missing")
        );
        assertThat(loaded.get("message-1"))
                .isEqualTo(TaskItemResult.succeeded("{\"valid\":true}"));
        assertThat(loaded).containsEntry("missing", null);

        var page = runtime.scanTaskItemResults(
                "task-1",
                "0",
                1000
        );
        assertThat(page.nextCursor()).isEqualTo("0");
        assertThat(page.results()).containsExactly(
                Map.entry(
                        "message-1",
                        TaskItemResult.succeeded("{\"valid\":true}")
                )
        );
    }

    @Test
    void taskItemTargetsAreOwnedByOnDemandTasksOnly() {
        long createdAt = redisTimeMillis();
        storeTask("on-demand-targets", "ON_DEMAND_ITEM_RULE");
        TaskItem targeted = new TaskItem(
                "message-targeted",
                "event",
                createdAt,
                Map.of(),
                0,
                createdAt + 60_000,
                TaskItemWorkerSelector.parse(Map.of("workerId", List.of("worker-b", "worker-a")))
        );

        assertThat(runtime.appendItems(
                "on-demand-targets",
                List.of(targeted)
        ).get("message-targeted").status()).isEqualTo(
                TaskItemAppendStatus.APPENDED
        );
        assertThat(runtime.loadTaskItems(
                "on-demand-targets",
                List.of("message-targeted")
        )).containsEntry("message-targeted", targeted);

        storeTask("precomputed-targets", "PRECOMPUTED_TASK_RULE");
        assertThat(runtime.appendItems(
                "precomputed-targets",
                List.of(new TaskItem(
                        "message-invalid",
                        "event",
                        createdAt,
                        Map.of(),
                        0,
                        createdAt + 60_000,
                        TaskItemWorkerSelector.parse(Map.of("workerId", List.of("worker-a")))
                ))
        ).get("message-invalid").status()).isEqualTo(
                TaskItemAppendStatus.INVALID
        );
        assertThat(redis.hlen(
                keyspace.base() + ":task:precomputed-targets:items"
        )).isZero();
    }

    @Test void opaquePropertySelectorRoundTripsWithoutKernelInterpretationAndPrecomputedRejectsIt() {
        long now = redisTimeMillis();
        var selector = TaskItemWorkerSelector.parse(Map.of("worker.test.region", List.of("east", "west")));
        var item = new TaskItem("indexed", "event", now, Map.of(), 0, now + 60_000, selector);
        storeTask("indexed-task", "ON_DEMAND_ITEM_RULE");
        assertThat(runtime.appendItems("indexed-task", List.of(item)).get("indexed").status()).isEqualTo(TaskItemAppendStatus.APPENDED);
        assertThat(runtime.loadTaskItems("indexed-task", List.of("indexed"))).containsEntry("indexed", item);
        assertThat(Jsons.parseObject(redis.hget(keyspace.base() + ":task:indexed-task:items", "indexed")))
                .containsEntry("workerSelector", selector.expression())
                .doesNotContainKeys("indexQuery", "targetWorkerIds");
        storeTask("indexed-precomputed", "PRECOMPUTED_TASK_RULE");
        assertThat(runtime.appendItems("indexed-precomputed", List.of(item)).get("indexed").status()).isEqualTo(TaskItemAppendStatus.INVALID);
        assertThat(redis.hlen(keyspace.base() + ":task:indexed-precomputed:items")).isZero();

    }

    @Test
    void legacyAndMissingSelectorStorageCannotBecomeAny() {
        long now = redisTimeMillis();
        var common = new LinkedHashMap<String, Object>();
        common.put("eventCode", "event");
        common.put("payload", Map.of());
        common.put("priority", 0);
        common.put("createdAtMillis", now);
        common.put("expireAtMillis", now + 60_000);
        var oldIds = new LinkedHashMap<>(common);
        oldIds.put("targetWorkerIds", List.of("worker"));
        var oldQuery = new LinkedHashMap<>(oldIds);
        oldQuery.put("indexQuery", Map.of("indexId", "country", "value", "CN"));
        var wrapped = new LinkedHashMap<>(common);
        wrapped.put("workerSelector", Map.of("expression", Map.of()));
        var missingValue = new LinkedHashMap<>(common);
        missingValue.put("workerSelector", null);
        var mixed = new LinkedHashMap<>(oldIds);
        mixed.put("workerSelector", Map.of());
        var malformed = new LinkedHashMap<>(common);
        malformed.put("workerSelector", Map.of("workerId", List.of(123)));
        var oldAny = new LinkedHashMap<>(common);
        oldAny.put("workerSelector", List.of());
        var oldTriple = new LinkedHashMap<>(common);
        oldTriple.put("workerSelector", List.of("workerId", "$eq", "worker"));
        for (Map<String, Object> record : List.of(common, oldIds, oldQuery, wrapped, missingValue,
                mixed, malformed, oldAny, oldTriple)) {
            String encoded = Jsons.toJson(record);
            redis.hset(keyspace.base() + ":task:old-shape:items", "item", encoded);
            assertThat(runtime.loadTaskItems("old-shape", List.of("item"))).containsEntry("item", null);
            assertThat(redis.hget(keyspace.base() + ":task:old-shape:items", "item")).isEqualTo(encoded);
        }
    }

    @Test
    void failedResultsAreIdempotentAndSuccessIsAbsorbing() {
        runtime.storeTaskItemFailedResults(
                "task-1",
                List.of("message-failed", "message-late", "message-late")
        );
        runtime.storeTaskItemFailedResults(
                "task-1",
                List.of("message-failed")
        );

        assertThat(redis.hget(
                keyspace.base() + ":task:task-1:results",
                "message-failed"
        )).isEqualTo(
                "{\"code\":\"failed\","
                        + "\"opaqueResultPayload\":"
                        + "\"TaskItem ended without a successful result\"}"
        );

        var initiallyLoaded = runtime.loadTaskItemResults(
                "task-1",
                List.of("message-failed", "message-late", "missing")
        );
        assertThat(initiallyLoaded)
                .containsEntry("message-failed", TaskItemResult.failed())
                .containsEntry("message-late", TaskItemResult.failed())
                .containsEntry("missing", null);

        runtime.storeTaskItemSuccessResults("task-1", successResults(Map.of("message-late", "late-success"), 2000));
        runtime.storeTaskItemSuccessResults("task-1", successResults(Map.of("message-late", "newer-success"), 3000));
        runtime.storeTaskItemFailedResults(
                "task-1",
                List.of("message-late")
        );

        assertThat(runtime.loadTaskItemResults(
                "task-1",
                List.of("message-failed", "message-late")
        )).containsExactly(
                Map.entry("message-failed", TaskItemResult.failed()),
                Map.entry(
                        "message-late",
                        TaskItemResult.succeeded("newer-success")
                )
        );
        assertThat(runtime.scanTaskItemResults("task-1", "0", 1000)
                .results()).containsExactlyInAnyOrderEntriesOf(Map.of(
                        "message-failed", TaskItemResult.failed(),
                        "message-late",
                        TaskItemResult.succeeded("newer-success")
                ));
    }

    @Test
    void successValidationFailureDoesNotPartiallyWriteTheBatch() {
        var results = new java.util.LinkedHashMap<String, String>();
        results.put("message-valid", "payload");
        results.put("message-invalid", "");

        assertThatThrownBy(() -> runtime.storeTaskItemSuccessResults("task-1", successResults(results, 4000))).isInstanceOf(IllegalArgumentException.class);

        assertThat(redis.hlen(
                keyspace.base() + ":task:task-1:results"
        )).isZero();
    }

    @Test
    void resultWritesRejectAWrongRedisTypeWithoutReplacingIt() {
        String resultsKey = keyspace.base() + ":task:task-1:results";
        redis.set(resultsKey, "corrupt-type");

        assertThatThrownBy(() -> runtime.storeTaskItemSuccessResults("task-1", successResults(Map.of("message-success", "payload"), 5000))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> runtime.storeTaskItemFailedResults(
                "task-1",
                List.of("message-failed")
        )).isInstanceOf(RuntimeException.class);

        assertThat(redis.get(resultsKey)).isEqualTo("corrupt-type");
    }

    @Test
    void executionResultsKeepOpaqueWhitespacePayloadCompatibility() {
        runtime.storeTaskItemSuccessResults("task-1", successResults(Map.of("rpc", " "), 5000));
        assertThat(runtime.loadTaskItemResults("task-1", List.of("rpc")))
                .containsEntry("rpc", TaskItemResult.succeeded(" "));
        runtime.storeTaskItemSuccessResults("task-1", successResults(Map.of("rpc", "\t"), 5001));
        assertThat(runtime.loadTaskItemResults("task-1", List.of("rpc")))
                .containsEntry("rpc", TaskItemResult.succeeded("\t"));
    }

    @Test
    void legacyAndCorruptResultValuesFailClosed() {
        String resultsKey = keyspace.base() + ":task:task-1:results";
        redis.hset(resultsKey, "legacy", "legacy-payload");

        assertThatThrownBy(() -> runtime.loadTaskItemResults(
                "task-1",
                List.of("legacy")
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("TaskItem Result is corrupt");

        redis.hdel(resultsKey, "legacy");
        redis.hset(resultsKey, "corrupt", "{");
        assertThatThrownBy(() -> runtime.scanTaskItemResults(
                "task-1",
                "0",
                1000
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("TaskItem Result is corrupt");
    }

    @Test
    void catalogReadsTheCanonicalDescriptorAndMissingAppendIsNarrow() {
        storeTask("task-1", "ON_DEMAND_ITEM_RULE");

        var descriptor = catalog.loadTaskAllocationDescriptors(
                List.of("task-1", "missing")
        );
        assertThat(descriptor.get("task-1").workerGroupId())
                .isEqualTo("phone-tools");
        assertThat(descriptor).containsEntry("missing", null);
        assertThat(runtime.appendItems(
                "missing",
                List.of(new TaskItem(
                        "message-1",
                        "event",
                        redisTimeMillis(),
                        Map.of(),
                        5,
                        redisTimeMillis() + 60_000,
                        TaskItemWorkerSelector.parse(Map.of())
                ))
        ).get("message-1").status()).isEqualTo(
                TaskItemAppendStatus.NOT_FOUND
        );
    }

    @Test
    void legacyRuleFieldsAreRejectedAtTheKernelStorageBoundary() {
        storeTask("legacy-task", "ON_DEMAND_ITEM_RULE");
        String descriptorKey = keyspace.base()
                + ":task:legacy-task:descriptor";
        redis.hset(descriptorKey, "allocationRuleJson", "{}");

        assertThatThrownBy(() -> catalog.loadTaskAllocationDescriptors(
                List.of("legacy-task")
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("Task descriptor is corrupt");

        redis.hset(
                keyspace.base() + ":task:legacy-task:items",
                "legacy-message",
                "{\"eventCode\":\"event\",\"payload\":{},"
                        + "\"priority\":1,\"createdAtMillis\":1,"
                        + "\"expireAtMillis\":2,\"allocationRule\":{}}"
        );
        assertThat(runtime.loadTaskItems(
                "legacy-task",
                List.of("legacy-message")
        )).containsEntry("legacy-message", null);
    }

    @Test
    void javaTaskCommandsCreateApproveCloseAndReleaseIdlePark() {
        TaskDescriptor descriptor = descriptor("task-commands", 7);

        assertThat(runtime.createTask(descriptor).status())
                .isEqualTo(TaskCreationStatus.CREATED);
        assertThat(redis.hgetall(
                keyspace.base() + ":task:task-commands:descriptor"
        )).isEqualTo(Map.of(
                "workerGroupId", "phone-tools",
                "workerAllocationMechanism", "ON_DEMAND_ITEM_RULE",
                "idleDisposition", "PARK_WHEN_IDLE",
                "configJson", "{\"maxRetryTimes\":\"3\","
                        + ""
                        + "\"priority\":\"7\"}"
        ));
        var created = scoreCore.getScoreStates(
                List.of("task-commands")
        ).get("task-commands");
        assertThat(created).isNotNull();
        assertThat(created.band()).isEqualTo(
                TaskScoreBandCore.TaskScoreBand.PRE_REVIEW
        );
        assertThat(created.suffix()).isEqualTo(1);

        assertThat(lifecycle.approveTask("task-commands").status())
                .isEqualTo(
                        TaskLifecycleCommands.TaskApprovalStatus.APPROVED
                );
        var approved = scoreCore.getScoreStates(
                List.of("task-commands")
        ).get("task-commands");
        assertThat(approved).isNotNull();
        assertThat(approved.band()).isEqualTo(
                TaskScoreBandCore.TaskScoreBand.RUNNING_VISIBLE
        );
        assertThat(approved.timeMillis()).isEqualTo(
                TaskScoreBandCore.INITIAL_TIME_MILLIS
        );
        assertThat(approved.suffix()).isEqualTo(92);

        redis.zadd(
                keyspace.base() + ":task:score",
                idleParkScore(),
                "task-commands"
        );
        long now = redisTimeMillis();
        TaskItem item = new TaskItem(
                "call-message",
                "extension.worker.string.md5",
                now,
                Map.of("value", "abc"),
                5,
                now + 60_000,
                TaskItemWorkerSelector.parse(Map.of())
        );
        var submitted = callSubmission.submit(
                "task-commands",
                List.of(item)
        );
        assertThat(submitted.status()).isEqualTo(
                TaskCallItemSubmission.TaskCallSubmissionStatus.SUBMITTED
        );
        assertThat(submitted.itemResults().get("call-message").status())
                .isEqualTo(TaskItemAppendStatus.APPENDED);
        var released = scoreCore.getScoreStates(
                List.of("task-commands")
        ).get("task-commands");
        assertThat(released).isNotNull();
        assertThat(released.band()).isEqualTo(
                TaskScoreBandCore.TaskScoreBand.RUNNING_VISIBLE
        );
        assertThat(released.score()).isLessThan(idleParkScore());

        assertThat(lifecycle.closeTask("task-commands").status())
                .isEqualTo(TaskLifecycleCommands.TaskCloseStatus.CLOSED);
        assertThat(lifecycle.closeTask("task-commands").status())
                .isEqualTo(
                        TaskLifecycleCommands.TaskCloseStatus.ALREADY_CLOSED
                );
        assertThat(scoreCore.getScoreStates(List.of("task-commands"))
                .get("task-commands").band()).isEqualTo(
                        TaskScoreBandCore.TaskScoreBand.TERMINAL
                );
    }

    @Test
    void createRecoversScoreOnlyInterruption() {
        assertThat(scoreCore.initializeScore("score-only", 1, 3_000)
                .status()).isEqualTo(
                        TaskScoreBandCore.TaskScoreTransitionStatus
                                .TRANSITIONED
                );
        assertThat(runtime.createTask(descriptor("score-only", 3)).status())
                .isEqualTo(TaskCreationStatus.CREATED);
        assertThat(runtime.createTask(descriptor("score-only", 3)).status())
                .isEqualTo(TaskCreationStatus.CONFLICT);

    }

    @Test
    void createRetainsDescriptorWhenLeaseReleaseCannotBeConfirmed() {
        String taskId = "create-release-failure";
        TaskScoreBandCore releaseFailure = mock(TaskScoreBandCore.class);
        when(releaseFailure.initializeScore(taskId, 1, 3_000L))
                .thenReturn(new TaskScoreBandCore.TaskScoreTransitionResult(
                        TaskScoreBandCore.TaskScoreTransitionStatus
                                .TRANSITIONED,
                        123L
                ));
        when(releaseFailure.releaseObservedScoreHold(taskId, 123L))
                .thenReturn(new TaskScoreBandCore.TaskScoreTransitionResult(
                        TaskScoreBandCore.TaskScoreTransitionStatus.STALE,
                        null
                ));

        try (RedisTaskRuntime interrupted = new RedisTaskRuntime(
                redisClient,
                releaseFailure,
                itemScoreCore,
                keyspace
        )) {
            assertThat(interrupted.createTask(
                    descriptor(taskId, 4)
            ).status()).isEqualTo(TaskCreationStatus.RETRYABLE);
        }

        assertThat(redis.hgetall(
                keyspace.base() + ":task:" + taskId + ":descriptor"
        )).containsEntry("workerGroupId", "phone-tools")
                .containsEntry("workerAllocationMechanism", "ON_DEMAND_ITEM_RULE")
                .containsEntry("idleDisposition", "PARK_WHEN_IDLE");
    }

    @Test
    void secondTryReleaseRepairsIdleParkCreatedDuringAppend() {
        String taskId = "park-during-append";
        assertThat(runtime.createTask(descriptor(taskId, 5)).status())
                .isEqualTo(TaskCreationStatus.CREATED);
        assertThat(lifecycle.approveTask(taskId).status()).isEqualTo(
                TaskLifecycleCommands.TaskApprovalStatus.APPROVED
        );

        TaskRuntime appendRuntime = mock(TaskRuntime.class);
        when(appendRuntime.appendItems(eq(taskId), anyList()))
                .thenAnswer(invocation -> {
                    List<TaskItem> submittedItems = invocation.getArgument(1);
                    redis.zadd(
                            keyspace.base() + ":task:score",
                            idleParkScore(),
                            taskId
                    );
                    return runtime.appendItems(taskId, submittedItems);
                });
        long now = redisTimeMillis();
        TaskItem item = new TaskItem(
                "message-during-park",
                "extension.worker.string.md5",
                now,
                Map.of("value", "abc"),
                5,
                now + 60_000,
                TaskItemWorkerSelector.parse(Map.of())
        );

        var submitted = new DefaultTaskCallItemSubmission(
                scoreCore,
                appendRuntime
        ).submit(taskId, List.of(item));

        assertThat(submitted.status()).isEqualTo(
                TaskCallItemSubmission.TaskCallSubmissionStatus.SUBMITTED
        );
        assertThat(submitted.itemResults()
                .get("message-during-park").status()).isEqualTo(
                        TaskItemAppendStatus.APPENDED
                );
        var state = scoreCore.getScoreStates(List.of(taskId)).get(taskId);
        assertThat(state).isNotNull();
        assertThat(state.band()).isEqualTo(
                TaskScoreBandCore.TaskScoreBand.RUNNING_VISIBLE
        );
        assertThat(state.score()).isLessThan(idleParkScore());
    }

    @Test
    void preReviewStartAndInitialPromotionUseExactObservedScores() {
        var initialized = scoreCore.initializeScore("rewrite-task", 8, 3_000);
        assertThat(initialized.status()).isEqualTo(
                TaskScoreBandCore.TaskScoreTransitionStatus.TRANSITIONED
        );
        assertThat(initialized.score()).isNotNull();
        var released = scoreCore.releaseObservedScoreHold(
                "rewrite-task",
                initialized.score()
        );
        assertThat(released.status()).isEqualTo(
                TaskScoreBandCore.TaskScoreTransitionStatus.TRANSITIONED
        );
        var releasedState = scoreCore.getScoreStates(
                List.of("rewrite-task", "missing")
        );
        assertThat(releasedState.get("missing")).isNull();
        var started = scoreCore.startObservedPreReviewTask(
                "rewrite-task",
                released.score(),
                4
        );
        assertThat(started.status()).isEqualTo(
                TaskScoreBandCore.TaskScoreTransitionStatus.TRANSITIONED
        );
        var initial = scoreCore.getScoreStates(List.of("rewrite-task"))
                .get("rewrite-task");
        assertThat(initial.band()).isEqualTo(
                TaskScoreBandCore.TaskScoreBand.RUNNING_VISIBLE
        );
        assertThat(initial.timeMillis()).isEqualTo(
                TaskScoreBandCore.INITIAL_TIME_MILLIS
        );
        assertThat(initial.suffix()).isEqualTo(95);

        var promoted = scoreCore.promoteObservedInitialTasks(Map.of(
                "rewrite-task",
                initial.score()
        )).get("rewrite-task");
        assertThat(promoted.status()).isEqualTo(
                TaskScoreBandCore.TaskScoreTransitionStatus.TRANSITIONED
        );
        assertThat(scoreCore.promoteObservedInitialTasks(Map.of(
                "rewrite-task",
                initial.score()
        )).get("rewrite-task").status()).isEqualTo(
                TaskScoreBandCore.TaskScoreTransitionStatus.STALE
        );
        assertThat(scoreCore.releaseObservedScoreHold(
                "rewrite-task",
                initialized.score()
        ).status()).isEqualTo(
                TaskScoreBandCore.TaskScoreTransitionStatus.STALE
        );
    }

    @Test
    void initialPromotionUsesOneRedisTimeAndIndependentExactResults() {
        Map<String, Long> initialScores = new java.util.LinkedHashMap<>();
        for (String taskId : List.of("batch-a", "batch-b", "batch-c")) {
            var initialized = scoreCore.initializeScore(taskId, 1, 3_000);
            var released = scoreCore.releaseObservedScoreHold(
                    taskId,
                    initialized.score()
            );
            var started = scoreCore.startObservedPreReviewTask(
                    taskId,
                    released.score(),
                    10
            );
            initialScores.put(taskId, started.score());
        }
        assertThat(scoreCore.closeScore(
                "batch-b",
                TaskScoreBandCore.TERMINAL_SCORE_MAX
        ).status()).isEqualTo(
                TaskScoreBandCore.TaskScoreTransitionStatus.TRANSITIONED
        );
        initialScores.put(
                "batch-missing",
                taskScore(
                        TaskScoreBandCore.RUNNING_VISIBLE_TAG,
                        TaskScoreBandCore.INITIAL_TIME_SLOT,
                        TaskScoreBandCore.MAX_SUFFIX
                )
        );

        var results = scoreCore.promoteObservedInitialTasks(initialScores);

        assertThat(results.keySet()).containsExactlyElementsOf(
                initialScores.keySet()
        );
        assertThat(results.get("batch-a").status()).isEqualTo(
                TaskScoreBandCore.TaskScoreTransitionStatus.TRANSITIONED
        );
        assertThat(results.get("batch-c").status()).isEqualTo(
                TaskScoreBandCore.TaskScoreTransitionStatus.TRANSITIONED
        );
        assertThat(results.get("batch-a").score()).isEqualTo(
                results.get("batch-c").score()
        );
        assertThat(results.get("batch-b").status()).isEqualTo(
                TaskScoreBandCore.TaskScoreTransitionStatus.STALE
        );
        assertThat(results.get("batch-missing").status()).isEqualTo(
                TaskScoreBandCore.TaskScoreTransitionStatus.STALE
        );
    }

    @Test
    void initialSlotUsesPrioritySuffixOrder() {
        Map<String, Integer> priorities = Map.of(
                "priority-0", 0,
                "priority-1", 1,
                "priority-99", 99
        );
        for (var entry : priorities.entrySet()) {
            var initialized = scoreCore.initializeScore(
                    entry.getKey(),
                    1,
                    3_000
            );
            var released = scoreCore.releaseObservedScoreHold(
                    entry.getKey(),
                    initialized.score()
            );
            assertThat(scoreCore.startObservedPreReviewTask(
                    entry.getKey(),
                    released.score(),
                    entry.getValue()
            ).status()).isEqualTo(
                    TaskScoreBandCore.TaskScoreTransitionStatus.TRANSITIONED
            );
        }

        var states = scoreCore.getScoreStates(List.copyOf(priorities.keySet()));
        assertThat(states.get("priority-0").timeMillis()).isEqualTo(
                TaskScoreBandCore.INITIAL_TIME_MILLIS
        );
        assertThat(states.get("priority-0").suffix()).isEqualTo(99);
        assertThat(states.get("priority-1").timeMillis()).isEqualTo(
                TaskScoreBandCore.INITIAL_TIME_MILLIS
        );
        assertThat(states.get("priority-1").suffix()).isEqualTo(98);
        assertThat(states.get("priority-99").timeMillis()).isEqualTo(
                TaskScoreBandCore.INITIAL_TIME_MILLIS
        );
        assertThat(states.get("priority-99").suffix()).isZero();
        long lowSlotScore = taskScore(
                TaskScoreBandCore.RUNNING_VISIBLE_TAG,
                TaskScoreBandCore.INITIAL_TIME_SLOT - 1,
                TaskScoreBandCore.MAX_SUFFIX
        );
        redis.zadd(
                keyspace.base() + ":task:score",
                lowSlotScore,
                "other-low-slot"
        );
        var schedulingScores = scoreCore.acquireSchedulingTasks(4);
        assertThat(schedulingScores.keySet())
                .containsExactly(
                        "priority-0",
                        "priority-1",
                        "priority-99",
                        "other-low-slot"
                );
        assertThat(scoreCore.filterInitialTaskScores(schedulingScores)
                .keySet()).containsExactly(
                        "priority-0",
                        "priority-1",
                        "priority-99"
                );
        assertThat(scoreCore.promoteObservedInitialTasks(Map.of(
                "other-low-slot",
                lowSlotScore
        )).get("other-low-slot").status()).isEqualTo(
                TaskScoreBandCore.TaskScoreTransitionStatus.INVALID
        );
        assertThat(scoreCore.rewriteSameBandTimeMillis(
                "priority-0",
                TaskScoreBandCore.TaskScoreBand.RUNNING_VISIBLE,
                redisTimeMillis() + 1_000
        ).status()).isEqualTo(
                TaskScoreBandCore.TaskScoreTransitionStatus.STALE
        );
        assertThat(scoreCore.parkObservedIdleTask(
                "priority-0",
                states.get("priority-0").score()
        ).status()).isEqualTo(
                TaskScoreBandCore.TaskScoreTransitionStatus.INVALID
        );
    }

    @Test
    void equalPriorityInitialTasksHaveNoOrderingContract() {
        for (String taskId : List.of("equal-a", "equal-b")) {
            var initialized = scoreCore.initializeScore(taskId, 1, 3_000);
            var released = scoreCore.releaseObservedScoreHold(
                    taskId,
                    initialized.score()
            );
            assertThat(scoreCore.startObservedPreReviewTask(
                    taskId,
                    released.score(),
                    50
            ).status()).isEqualTo(
                    TaskScoreBandCore.TaskScoreTransitionStatus.TRANSITIONED
            );
        }

        var states = scoreCore.getScoreStates(List.of("equal-a", "equal-b"));
        assertThat(states.get("equal-a").timeMillis()).isEqualTo(
                TaskScoreBandCore.INITIAL_TIME_MILLIS
        );
        assertThat(states.get("equal-a").suffix()).isEqualTo(49);
        assertThat(states.get("equal-b").score()).isEqualTo(
                states.get("equal-a").score()
        );
        assertThat(scoreCore.acquireSchedulingTasks(2).keySet())
                .containsExactlyInAnyOrder("equal-a", "equal-b");
    }

    @Test
    void runningCountIncludesInitialNormalHoldParkAndPause() {
        String scoreKey = keyspace.base() + ":task:score";
        long normalStartSlot = TaskScoreBandCore.NORMAL_TIME_MIN_MILLIS
                / TaskScoreBandCore.SLOT_MILLIS;
        for (int index = 0; index < 97; index++) {
            redis.zadd(scoreKey, taskScore(
                    TaskScoreBandCore.RUNNING_VISIBLE_TAG,
                    normalStartSlot + index,
                    0
            ), "running-" + index);
        }
        redis.zadd(scoreKey, taskScore(
                TaskScoreBandCore.RUNNING_VISIBLE_TAG,
                redisTimeMillis() / TaskScoreBandCore.SLOT_MILLIS + 600,
                0
        ), "future-hold");
        redis.zadd(scoreKey, idleParkScore(), "idle-park");
        redis.zadd(scoreKey, taskScore(
                TaskScoreBandCore.RUNNING_VISIBLE_TAG,
                TaskScoreBandCore.PAUSE_TIME_SLOT,
                TaskScoreBandCore.MAX_SUFFIX
        ), "pause");

        var initialized = scoreCore.initializeScore(
                "waiting-review",
                1,
                3_000
        );
        var released = scoreCore.releaseObservedScoreHold(
                "waiting-review",
                initialized.score()
        );
        assertThat(scoreCore.countRunningTasks()).isEqualTo(100);
        assertThat(scoreCore.startObservedPreReviewTask(
                "waiting-review",
                released.score(),
                0
        ).status()).isEqualTo(
                TaskScoreBandCore.TaskScoreTransitionStatus.TRANSITIONED
        );
        assertThat(scoreCore.getScoreStates(List.of("waiting-review"))
                .get("waiting-review").band()).isEqualTo(
                        TaskScoreBandCore.TaskScoreBand.RUNNING_VISIBLE
                );
        assertThat(scoreCore.countRunningTasks()).isEqualTo(101);

        assertThat(scoreCore.closeScore(
                "running-0",
                TaskScoreBandCore.TERMINAL_SCORE_MAX
        ).status()).isEqualTo(
                TaskScoreBandCore.TaskScoreTransitionStatus.TRANSITIONED
        );
        assertThat(scoreCore.countRunningTasks()).isEqualTo(100);
    }

    @Test
    void concurrentPreReviewStartsUseIndependentExactCas() throws Exception {
        int taskCount = 120;
        Map<String, Long> observations = new java.util.LinkedHashMap<>();
        for (int index = 0; index < taskCount; index++) {
            String taskId = "concurrent-" + index;
            var initialized = scoreCore.initializeScore(taskId, 1, 3_000);
            var released = scoreCore.releaseObservedScoreHold(
                    taskId,
                    initialized.score()
            );
            observations.put(taskId, released.score());
        }

        List<Callable<TaskScoreBandCore.TaskScoreTransitionResult>> calls =
                new ArrayList<>();
        observations.forEach((taskId, observedScore) -> calls.add(() ->
                scoreCore.startObservedPreReviewTask(
                        taskId,
                        observedScore,
                        0
                )));
        List<TaskScoreBandCore.TaskScoreTransitionResult> results =
                new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var future : executor.invokeAll(calls)) {
                results.add(future.get());
            }
        }

        assertThat(results).filteredOn(result -> result.status()
                == TaskScoreBandCore.TaskScoreTransitionStatus.TRANSITIONED)
                .hasSize(taskCount);
        assertThat(scoreCore.countRunningTasks()).isEqualTo(taskCount);
    }

    @Test
    void schedulingTaskScanReadsDueNormalThenInitialInDescendingOrder() {
        String scoreKey = keyspace.base() + ":task:score";
        long nowSlot = redisTimeMillis() / TaskScoreBandCore.SLOT_MILLIS;
        redis.zadd(scoreKey, taskScore(
                TaskScoreBandCore.RUNNING_VISIBLE_TAG,
                nowSlot - 2,
                0
        ), "running-old");
        redis.zadd(scoreKey, taskScore(
                TaskScoreBandCore.RUNNING_VISIBLE_TAG,
                nowSlot - 1,
                0
        ), "running-new");
        redis.zadd(scoreKey, taskScore(
                TaskScoreBandCore.RUNNING_VISIBLE_TAG,
                nowSlot + 100,
                0
        ), "running-not-due");
        redis.zadd(scoreKey, taskScore(
                TaskScoreBandCore.RUNNING_VISIBLE_TAG,
                nowSlot + 200,
                0
        ), "running-future");
        redis.zadd(scoreKey, taskScore(
                TaskScoreBandCore.RUNNING_VISIBLE_TAG,
                TaskScoreBandCore.INITIAL_TIME_SLOT,
                TaskScoreBandCore.MAX_SUFFIX
        ), "initial");
        redis.zadd(scoreKey, idleParkScore(), "idle-park");
        redis.zadd(scoreKey, taskScore(
                TaskScoreBandCore.RUNNING_VISIBLE_TAG,
                TaskScoreBandCore.PAUSE_TIME_SLOT,
                TaskScoreBandCore.MAX_SUFFIX
        ), "pause");
        redis.zadd(scoreKey, taskScore(
                TaskScoreBandCore.PRE_REVIEW_TAG,
                nowSlot - 1,
                0
        ), "pre-review");
        redis.zadd(
                scoreKey,
                TaskScoreBandCore.TERMINAL_SCORE_MAX,
                "terminal"
        );
        Map<String, Double> before = redis.zrangeWithScores(scoreKey, 0, -1)
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        io.lettuce.core.ScoredValue::getValue,
                        io.lettuce.core.ScoredValue::getScore
                ));

        var schedulingScores = scoreCore.acquireSchedulingTasks(100);
        assertThat(schedulingScores.keySet())
                .containsExactly("running-new", "running-old", "initial");
        assertThat(scoreCore.filterInitialTaskScores(schedulingScores))
                .containsOnlyKeys("initial");
        assertThat(redis.zrangeWithScores(scoreKey, 0, -1).stream()
                .collect(java.util.stream.Collectors.toMap(
                        io.lettuce.core.ScoredValue::getValue,
                        io.lettuce.core.ScoredValue::getScore
                ))).isEqualTo(before);
    }

    @Test
    void schedulingTaskScanFiltersInvalidScoresWithoutRefill() {
        String scoreKey = keyspace.base() + ":task:score";
        long nowSlot = redisTimeMillis() / TaskScoreBandCore.SLOT_MILLIS;
        long newestScore = taskScore(
                TaskScoreBandCore.RUNNING_VISIBLE_TAG,
                nowSlot - 1,
                0
        );
        long olderScore = taskScore(
                TaskScoreBandCore.RUNNING_VISIBLE_TAG,
                nowSlot - 3,
                0
        );
        redis.zadd(scoreKey, newestScore, "valid-newest");
        redis.zadd(
                scoreKey,
                taskScore(
                        TaskScoreBandCore.RUNNING_VISIBLE_TAG,
                        nowSlot - 2,
                        0
                ) + 0.5D,
                "invalid-fractional"
        );
        redis.zadd(scoreKey, olderScore, "valid-older");

        var page = scoreCore.acquireSchedulingTasks(2);

        assertThat(page.keySet())
                .containsExactly("valid-newest");
    }

    @Test
    void schedulingTaskScanAppliesOneHundredMemberOwnerLimit() {
        String scoreKey = keyspace.base() + ":task:score";
        long nowSlot = redisTimeMillis() / TaskScoreBandCore.SLOT_MILLIS;
        for (int index = 0; index < 101; index++) {
            redis.zadd(scoreKey, taskScore(
                    TaskScoreBandCore.RUNNING_VISIBLE_TAG,
                    nowSlot - index - 1,
                    0
            ), "bounded-" + index);
        }

        var page = scoreCore.acquireSchedulingTasks(100);

        assertThat(page).hasSize(100);
        assertThat(page.keySet())
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.range(0, 100)
                                .mapToObj(index -> "bounded-" + index)
                                .toList()
                );
    }

    @Test
    void taskScorePreviewIsBoundedAndPreservesDescendingOwnerOrder() {
        String scoreKey = keyspace.base() + ":task:score";
        redis.zadd(scoreKey, -1, "terminal");
        redis.zadd(scoreKey, taskScore(
                TaskScoreBandCore.RUNNING_VISIBLE_TAG,
                1,
                0
        ), "initial");
        redis.zadd(scoreKey, taskScore(
                TaskScoreBandCore.RUNNING_VISIBLE_TAG,
                TaskScoreBandCore.NORMAL_TIME_MIN_MILLIS
                        / TaskScoreBandCore.SLOT_MILLIS,
                0
        ), "normal");
        redis.zadd(scoreKey, taskScore(
                TaskScoreBandCore.PRE_REVIEW_TAG,
                3,
                4
        ), "review");

        var fourBands = scoreCore.previewScoreStates(4);
        assertThat(fourBands).extracting(
                TaskScoreBandCore.TaskScoreState::taskId
        ).containsExactly("review", "normal", "initial", "terminal");
        assertThat(fourBands).extracting(
                TaskScoreBandCore.TaskScoreState::band
        ).containsExactly(
                TaskScoreBandCore.TaskScoreBand.PRE_REVIEW,
                TaskScoreBandCore.TaskScoreBand.RUNNING_VISIBLE,
                TaskScoreBandCore.TaskScoreBand.RUNNING_VISIBLE,
                TaskScoreBandCore.TaskScoreBand.TERMINAL
        );

        redis.del(scoreKey);
        for (int index = 1; index <= 1001; index++) {
            redis.zadd(
                    scoreKey,
                    taskScore(
                            TaskScoreBandCore.RUNNING_VISIBLE_TAG,
                            index,
                            0
                    ),
                    "task-" + index
            );
        }
        var bounded = scoreCore.previewScoreStates(1000);
        assertThat(bounded).hasSize(1000);
        assertThat(bounded.getFirst().taskId()).isEqualTo("task-1001");
        assertThat(bounded.getLast().taskId()).isEqualTo("task-2");
        assertThat(scoreCore.previewScoreStates(1)).hasSize(1);
        assertThatThrownBy(() -> scoreCore.previewScoreStates(1001))
                .isInstanceOf(IllegalArgumentException.class);

        redis.del(scoreKey);
        redis.zadd(scoreKey, 0.5, "corrupt");
        assertThatThrownBy(() -> scoreCore.previewScoreStates(1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integer");
    }

    @Test
    void publicFiniteTaskControlUsesJavaOwnersWithoutPythonHttp() {
        TaskControlController controller = new TaskControlController(
                mock(TaskCreationService.class),
                new TaskLifecycleService(lifecycle, catalog)
        );
        var created = runtime.createTask(new TaskDescriptor(
                "public-task",
                "phone-tools",
                WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE,
                TaskIdleDisposition.CLOSE_WHEN_IDLE,
                Map.of("priority", "2", "maximumCandidateWorkers", "1", "maxRetryTimes", "3")
        ));

        assertThat(created.status()).isEqualTo(TaskCreationStatus.CREATED);
        assertThat(controller.approveTask("public-task").status()
                .wireValue()).isEqualTo("applied");
        assertThat(controller.closeTask("public-task").status()
                .wireValue()).isEqualTo("applied");
    }

    @Test
    void everyTerminalTagLeavesSchedulingAndCanAdvanceWithinItsTag() {
        for (int tag = 2; tag <= 9; tag++) {
            String id = "item-" + tag;
            itemScoreCore.initializeItemScores("outcomes", Map.of(id, 0L), 3);
            var active = itemScoreCore.getItemScoreStates("outcomes", List.of(id)).get(id);
            assertThat(active.tag()).isEqualTo(1);
            assertThat(active.remainingBudget()).isEqualTo(4);
            assertThat(itemScoreCore.promoteItemOutcomes("outcomes", outcomeTargets(List.of(id), tag, 1_099))
                    .get(id).status()).isEqualTo(TRANSITIONED);
            for (long time : List.of(1_099L, 1_000L, 0L)) {
                assertThat(itemScoreCore.promoteItemOutcomes("outcomes", outcomeTargets(List.of(id), tag, time))
                        .get(id).status()).isEqualTo(NOOP);
            }
            assertThat(itemScoreCore.promoteItemOutcomes("outcomes", outcomeTargets(List.of(id), tag, 1_100))
                    .get(id).status()).isEqualTo(TRANSITIONED);
            var state = itemScoreCore.getItemScoreStates("outcomes", List.of(id)).get(id);
            assertThat(state.band()).isEqualTo(TaskItemScoreBandCore.TaskItemScoreBand.TERMINAL);
            assertThat(state.tag()).isEqualTo(tag);
            assertThat(state.timeMillis()).isEqualTo(1_100);
            assertThat(state.remainingBudget()).isNull();
            assertThat(itemScoreCore.initializeItemScores("outcomes", Map.of(id, 2_000L), 0)
                    .get(id).status()).isEqualTo(NOOP);
            assertThat(itemScoreCore.rewriteObservedItemScores("outcomes", Map.of(id, active.score()), 3_000, -1)
                    .get(id).status()).isEqualTo(STALE);
        }
        assertThat(itemScoreCore.acquireItemScoreCandidates("outcomes", 100)).isEmpty();
        assertThat(itemScoreCore.hasActiveItems(List.of("outcomes"))).containsEntry("outcomes", false);
        assertThat(itemScoreCore.hasDueActiveItems(List.of("outcomes"))).containsEntry("outcomes", false);
    }

    @Test
    void terminalOrderingUsesTheWholeScoreIncludingEarlierTimesInHigherTags() {
        itemScoreCore.initializeItemScores("outcomes", Map.of("item", TaskItemScoreBandCore.MAX_TIME_MILLIS), 98);
        for (int tag = 2; tag <= 9; tag++) {
            assertThat(itemScoreCore.promoteItemOutcomes("outcomes", outcomeTargets(List.of("item"), tag, 0))
                    .get("item").status()).isEqualTo(TRANSITIONED);
            assertThat(itemScoreCore.promoteItemOutcomes("outcomes", outcomeTargets(List.of("item"), tag, TaskItemScoreBandCore.MAX_TIME_MILLIS)).get("item").status()).isEqualTo(TRANSITIONED);
            if (tag > 2) {
                assertThat(itemScoreCore.promoteItemOutcomes("outcomes", outcomeTargets(List.of("item"), tag - 1, TaskItemScoreBandCore.MAX_TIME_MILLIS)).get("item").status()).isEqualTo(NOOP);
            }
        }
        assertThat(itemScoreCore.getItemScoreStates("outcomes", List.of("item"))
                .get("item").timeMillis()).isEqualTo(TaskItemScoreBandCore.MAX_TIME_MILLIS);
    }

    @Test
    void invalidAndCorruptOutcomesNeverCreateOrOverwriteMembers() {
        String key = keyspace.base() + ":task:outcomes:item_score";
        for (double raw : new double[]{0, -1, 1.5, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, 10 * TaskItemScoreBandCore.TAG_FACTOR,
                2 * TaskItemScoreBandCore.TAG_FACTOR + 1,
                TaskItemScoreBandCore.TAG_FACTOR + 0.5}) {
            redis.zadd(key, raw, "corrupt");
            assertThat(itemScoreCore.promoteItemOutcomes("outcomes", outcomeTargets(List.of("corrupt"), 9, 1_000))
                    .get("corrupt").status()).isEqualTo(CORRUPT);
            assertThat(redis.zscore(key, "corrupt")).isEqualTo(raw);
            assertThatThrownBy(() -> itemScoreCore.getItemScoreStates("outcomes", List.of("corrupt")))
                    .isInstanceOf(IllegalStateException.class);
        }
        for (int tag : new int[]{-1, 0, 1, 10}) {
            assertThat(itemScoreCore.promoteItemOutcomes("outcomes", outcomeTargets(List.of("missing"), tag, 0))
                    .get("missing").status()).isEqualTo(INVALID);
        }
        for (long time : new long[]{-1, TaskItemScoreBandCore.MAX_TIME_MILLIS + 1, Long.MAX_VALUE}) {
            assertThat(itemScoreCore.promoteItemOutcomes("outcomes", outcomeTargets(List.of("missing"), 2, time))
                    .get("missing").status()).isEqualTo(INVALID);
        }
        assertThat(itemScoreCore.promoteItemOutcomes("outcomes", outcomeTargets(List.of("missing"), 2, 0))
                .get("missing").status()).isEqualTo(NOT_FOUND);
        assertThat(itemScoreCore.getItemScoreStates("outcomes", List.of("missing")))
                .containsEntry("missing", null);
        assertThat(itemScoreCore.promoteItemOutcomes("outcomes", outcomeTargets(List.of(" "), 2, 0))
                .get(" ").status()).isEqualTo(INVALID);
        assertThatThrownBy(() -> itemScoreCore.getItemScoreStates("outcomes", List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class);
        var oversized = IntStream.range(0, 101).mapToObj(i -> "id-" + i).toList();
        assertThat(itemScoreCore.promoteItemOutcomes("outcomes", outcomeTargets(oversized, 2, 0)).values())
                .allMatch(result -> result.status() == INVALID);
        assertThatThrownBy(() -> itemScoreCore.getItemScoreStates("outcomes", oversized))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(redis.zcard(key)).isEqualTo(1);
    }

    @Test
    void concurrentPromotionsKeepMaximumAndClaimsRequireTheExactActiveObservation() throws Exception {
        itemScoreCore.initializeItemScores("outcomes", Map.of("item", 0L), 2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var writes = IntStream.range(0, 100).mapToObj(index -> executor.submit(() -> {
                start.await();
                return itemScoreCore.promoteItemOutcomes("outcomes", outcomeTargets(List.of("item"), 2 + index % 8, index * 100L));
            })).toList();
            start.countDown();
            for (var write : writes) write.get();
            var maximum = itemScoreCore.getItemScoreStates("outcomes", List.of("item")).get("item");
            assertThat(maximum.tag()).isEqualTo(9);
            assertThat(maximum.timeMillis()).isEqualTo(9_500);

            for (int round = 0; round < 20; round++) {
                String id = "race-" + round;
                long observed = itemScoreCore.initializeItemScores("outcomes", Map.of(id, 0L), 2).get(id).score();
                var gate = new CountDownLatch(1);
                var claims = IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                    gate.await();
                    return itemScoreCore.rewriteObservedItemScores("outcomes", Map.of(id, observed),
                            1_000 + index * 100, -1).get(id).status();
                })).toList();
                gate.countDown();
                assertThat(List.of(claims.get(0).get(), claims.get(1).get()))
                        .containsExactlyInAnyOrder(TRANSITIONED, STALE);

                long held = itemScoreCore.getItemScoreStates("outcomes", List.of(id)).get(id).score();
                var race = new CountDownLatch(1);
                var terminal = executor.submit(() -> {
                    race.await();
                    return itemScoreCore.promoteItemOutcomes("outcomes", outcomeTargets(List.of(id), 2, 0));
                });
                var claim = executor.submit(() -> {
                    race.await();
                    return itemScoreCore.rewriteObservedItemScores("outcomes", Map.of(id, held), 2_000, -1);
                });
                race.countDown();
                terminal.get();
                claim.get();
                assertThat(itemScoreCore.getItemScoreStates("outcomes", List.of(id)).get(id).tag()).isEqualTo(2);
            }
        }
    }

    @Test
    void hundredItemPromotionAndStateQueryHaveConstantClientCommandBudgets() {
        storeTask("outcomes", "ON_DEMAND_ITEM_RULE");
        Map<String, Long> due = new LinkedHashMap<>();
        IntStream.range(0, 100).forEach(i -> due.put("id-" + i, 0L));
        itemScoreCore.initializeItemScores("outcomes", due, 0);
        catalog.loadTaskAllocationDescriptors(List.of("outcomes"));
        var calls = new CopyOnWriteArrayList<String>();
        var listener = new CommandListener() {
            @Override public void commandStarted(CommandStartedEvent event) {
                calls.add(event.getCommand().getType().toString());
            }
        };
        redisClient.addListener(listener);
        try {
            itemScoreCore.close();
            catalog.close();
            itemScoreCore.getItemScoreStates("outcomes", List.of("id-0"));
            catalog.loadTaskAllocationDescriptors(List.of("outcomes"));
            calls.clear();
            var ids = List.copyOf(due.keySet());
            var promoted = itemScoreCore.promoteItemOutcomes("outcomes", outcomeTargets(ids, 6, 1_000));
            assertThat(promoted.keySet()).containsExactlyElementsOf(ids);
            assertThat(promoted.values()).allMatch(result -> result.status() == TRANSITIONED);
            assertThat(calls).containsExactly("EVAL");
            calls.clear();
            assertThat(itemScoreCore.getItemScoreStates("outcomes", ids)).hasSize(100);
            assertThat(calls).containsExactly("ZMSCORE");
            calls.clear();
            var service = new com.xa.mass.server.task.TaskDataService(runtime, catalog,
                    new com.xa.mass.server.task.TaskItemMapper(), itemScoreCore,
                    new com.xa.mass.server.task.TaskItemOutcomeProperties(Map.of()),
                    org.mockito.Mockito.mock(com.xa.mass.workermatching.WorkerMatchingCatalog.class));
            assertThat(service.loadTaskItemStates("outcomes", ids).values())
                    .allMatch(state -> state.tag() == 6 && state.outcomeName().equals("succeeded"));
            assertThat(calls).containsExactly("HGETALL", "ZMSCORE");
            calls.clear();
            assertThat(itemScoreCore.promoteItemOutcomes("outcomes", outcomeTargets(List.of("id-1", "id-0", "id-1"), 7, 0))
                    .keySet()).containsExactly("id-1", "id-0");
            assertThat(calls).containsExactly("EVAL");
            calls.clear();
            assertThat(itemScoreCore.getItemScoreStates("outcomes", List.of("id-1", "missing", "id-1"))
                    .keySet()).containsExactly("id-1", "missing");
            assertThat(calls).containsExactly("ZMSCORE");
        } finally {
            redisClient.removeListener(listener);
        }
    }

    @Test
    void reappendPreservesTerminalAndLateSuccessAdvancesFailedWithoutReopeningTask() {
        runtime.createTask(descriptor("outcomes", 0));
        long now = redisTimeMillis();
        var item = new TaskItem("item", "event", now, Map.of(), 0, now + 60_000, TaskItemWorkerSelector.parse(Map.of()));
        runtime.appendItems("outcomes", List.of(item));
        runtime.storeTaskItemFailedResults("outcomes", List.of("item"));
        itemScoreCore.promoteItemOutcomes("outcomes", outcomeTargets(List.of("item"), 5, 5_000));
        scoreCore.closeScore("outcomes", -1);
        runtime.appendItems("outcomes", List.of(item));
        assertThat(itemScoreCore.getItemScoreStates("outcomes", List.of("item")).get("item").tag()).isEqualTo(5);
        new com.xa.mass.kernel.task.DefaultTaskItemResultEvents(runtime, itemScoreCore, 6)
                .onItemsSucceeded("outcomes", Map.of("item", "late"), 2_000);
        assertThat(itemScoreCore.getItemScoreStates("outcomes", List.of("item")).get("item").tag()).isEqualTo(6);
        assertThat(runtime.loadTaskItemResults("outcomes", List.of("item")))
                .containsEntry("item", TaskItemResult.succeeded("late"));
        assertThat(itemScoreCore.hasActiveItems(List.of("outcomes"))).containsEntry("outcomes", false);
        assertThat(scoreCore.getScoreStates(List.of("outcomes")).get("outcomes").score()).isEqualTo(-1);
    }

    @Test
    void heterogeneousObservationBatchUsesOneScoreAndAtMostOneResultCommand() {
        Map<String, Long> due = new LinkedHashMap<>();
        IntStream.range(0, 100).forEach(i -> due.put("id-" + i, 0L));
        itemScoreCore.initializeItemScores("tracked", due, 0);
        var observations = IntStream.range(0, 100).mapToObj(i ->
                new com.xa.mass.kernel.task.TaskItemResultEvents.TaskItemOutcomeObservation(
                        "id-" + i, 6 + i % 4, 1_001 + i, "reply-" + i)).toList();
        var events = new com.xa.mass.kernel.task.DefaultTaskItemResultEvents(runtime, itemScoreCore, 6);
        var calls = new CopyOnWriteArrayList<String>();
        var listener = new CommandListener() {
            @Override public void commandStarted(CommandStartedEvent event) {
                calls.add(event.getCommand().getType().toString());
            }
        };
        redisClient.addListener(listener);
        try {
            runtime.close();
            itemScoreCore.close();
            runtime.loadTaskItemResults("tracked", List.of("id-0"));
            itemScoreCore.getItemScoreStates("tracked", List.of("id-0"));
            calls.clear();
            events.onItemOutcomesObserved("tracked", observations);
            assertThat(calls).containsExactly("EVAL", "EVAL");
            var states = itemScoreCore.getItemScoreStates("tracked", List.copyOf(due.keySet()));
            var results = runtime.loadTaskItemResults("tracked", List.copyOf(due.keySet()));
            for (int i = 0; i < 100; i++) {
                assertThat(states.get("id-" + i).tag()).isEqualTo(6 + i % 4);
                assertThat(results.get("id-" + i)).isEqualTo(TaskItemResult.succeeded("reply-" + i));
            }
            calls.clear();
            events.onItemOutcomesObserved("tracked", observations.stream().map(o ->
                    new com.xa.mass.kernel.task.TaskItemResultEvents.TaskItemOutcomeObservation(
                            o.messageId(), o.tag(), 3_000, null)).toList());
            assertThat(calls).containsExactly("EVAL");
            assertThat(runtime.loadTaskItemResults("tracked", List.of("id-0")))
                    .containsEntry("id-0", TaskItemResult.succeeded("reply-0"));
        } finally {
            redisClient.removeListener(listener);
        }
    }

    @Test
    void concurrentResultUpdatesKeepHighestTagThenLatestMillis() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var writes = IntStream.range(0, 100).mapToObj(i -> executor.submit(() -> {
                start.await();
                runtime.storeTaskItemSuccessResults("tracked", Map.of("item",
                        new TaskItemSuccessResult(6 + i % 4, 1_000 + i, "reply-" + i)));
                return null;
            })).toList();
            start.countDown();
            for (var write : writes) write.get();
        }
        runtime.storeTaskItemSuccessResults("tracked", Map.of("item", new TaskItemSuccessResult(6, 9_000, "send")));
        runtime.storeTaskItemSuccessResults("tracked", Map.of("item", new TaskItemSuccessResult(9, 1_099, "same")));
        runtime.storeTaskItemFailedResults("tracked", List.of("item"));
        assertThat(runtime.loadTaskItemResults("tracked", List.of("item")))
                .containsEntry("item", TaskItemResult.succeeded("reply-99"));
        runtime.storeTaskItemSuccessResults("tracked", Map.of("item", new TaskItemSuccessResult(9, 1_100, "latest")));
        for (int read = 0; read < 2; read++) {
            assertThat(runtime.loadTaskItemResults("tracked", List.of("item")))
                    .containsEntry("item", TaskItemResult.succeeded("latest"));
        }
    }

    @Test
    void sameSlotContentUpdatesAndTerminalObservationsDoNotReopenTheTask() {
        runtime.createTask(descriptor("tracked", 0));
        itemScoreCore.initializeItemScores("tracked", Map.of("item", 0L), 0);
        scoreCore.closeScore("tracked", -1);
        var events = new com.xa.mass.kernel.task.DefaultTaskItemResultEvents(runtime, itemScoreCore, 6);
        events.onItemOutcomesObserved("tracked", List.of(
                new com.xa.mass.kernel.task.TaskItemResultEvents.TaskItemOutcomeObservation("item", 9, 1_001, "first")));
        long terminal = itemScoreCore.getItemScoreStates("tracked", List.of("item")).get("item").score();
        events.onItemOutcomesObserved("tracked", List.of(
                new com.xa.mass.kernel.task.TaskItemResultEvents.TaskItemOutcomeObservation("item", 9, 1_002, "latest")));
        events.onItemsSucceeded("tracked", Map.of("item", "send"), 9_000);
        assertThat(itemScoreCore.getItemScoreStates("tracked", List.of("item")).get("item").score()).isEqualTo(terminal);
        assertThat(runtime.loadTaskItemResults("tracked", List.of("item")))
                .containsEntry("item", TaskItemResult.succeeded("latest"));
        assertThat(scoreCore.getScoreStates(List.of("tracked")).get("tracked").score()).isEqualTo(-1);
        assertThat(itemScoreCore.hasActiveItems(List.of("tracked"))).containsEntry("tracked", false);
    }

    @Test
    void observationDoesNotCreateMissingOrCorruptItemsAndResultCorruptionIsNotOverwritten() {
        String scoreKey = keyspace.base() + ":task:tracked:item_score";
        redis.zadd(scoreKey, 1, "corrupt");
        var events = new com.xa.mass.kernel.task.DefaultTaskItemResultEvents(runtime, itemScoreCore, 6);
        events.onItemOutcomesObserved("tracked", List.of(
                new com.xa.mass.kernel.task.TaskItemResultEvents.TaskItemOutcomeObservation("missing", 9, 1_000, "missing"),
                new com.xa.mass.kernel.task.TaskItemResultEvents.TaskItemOutcomeObservation("corrupt", 9, 1_000, "corrupt")));
        assertThat(redis.zscore(scoreKey, "missing")).isNull();
        assertThat(runtime.loadTaskItemResults("tracked", List.of("missing", "corrupt")).values())
                .containsOnlyNulls();
        String resultKey = keyspace.base() + ":task:tracked:results";
        for (String corrupt : List.of("{", "{\"code\":\"200\",\"opaqueResultPayload\":\"legacy\"}",
                "{\"code\":\"200\",\"opaqueResultPayload\":\"bad\",\"tag\":9,\"observedAtMillis\":-1}")) {
            redis.hset(resultKey, "item", corrupt);
            assertThatThrownBy(() -> runtime.storeTaskItemSuccessResults("tracked",
                    Map.of("item", new TaskItemSuccessResult(9, 1_000, "replacement"))))
                    .isInstanceOf(RuntimeException.class).hasMessageContaining("Result is corrupt");
            assertThat(redis.hget(resultKey, "item")).isEqualTo(corrupt);
            assertThatThrownBy(() -> runtime.loadTaskItemResults("tracked", List.of("item")))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    private TaskDescriptor descriptor(String taskId, int priority) {
        return new TaskDescriptor(
                taskId,
                "phone-tools",
                WorkerAllocationMechanism.ON_DEMAND_ITEM_RULE,
                TaskIdleDisposition.PARK_WHEN_IDLE,
                config(priority)
        );
    }

    private static Map<String, String> config(int priority) {
        return Map.of(
                "priority", Integer.toString(priority),
                "maxRetryTimes", "3"
        );
    }

    private static long idleParkScore() {
        return (long) TaskScoreBandCore.RUNNING_VISIBLE_TAG
                * TaskScoreBandCore.DEFAULT_TAG_FACTOR
                + (TaskScoreBandCore.MAX_TIME_SLOT - 1)
                * TaskScoreBandCore.SUFFIX_FACTOR
                + TaskScoreBandCore.MAX_SUFFIX;
    }

    private static long taskScore(int tag, long timeSlot, int suffix) {
        return (long) tag * TaskScoreBandCore.DEFAULT_TAG_FACTOR
                + timeSlot * TaskScoreBandCore.SUFFIX_FACTOR
                + suffix;
    }

    private void storeTask(String taskId, String allocationMechanism) {
        redis.hset(
                keyspace.base() + ":task:" + taskId + ":descriptor",
                Map.of(
                        "workerGroupId", "phone-tools",
                        "workerAllocationMechanism", allocationMechanism,
                        "idleDisposition", "PARK_WHEN_IDLE",
                        "configJson",
                        "{\"maxRetryTimes\":\"3\","
                                + (allocationMechanism.equals("PRECOMPUTED_TASK_RULE") ? "\"maximumCandidateWorkers\":\"1\"," : "")
                                + "\"priority\":\"0\"}"
                )
        );
    }

    private long redisTimeMillis() {
        List<String> parts = redis.time();
        return Long.parseLong(parts.get(0)) * 1_000
                + Long.parseLong(parts.get(1)) / 1_000;
    }
    private static Map<String, TaskItemScoreBandCore.TaskItemOutcomeTarget> outcomeTargets(
            List<String> ids, int tag, long time
    ) {
        Map<String, TaskItemScoreBandCore.TaskItemOutcomeTarget> targets = new java.util.LinkedHashMap<>();
        ids.forEach(id -> targets.put(id, new TaskItemScoreBandCore.TaskItemOutcomeTarget(tag, time)));
        return targets;
    }

    private static Map<String, TaskItemSuccessResult> successResults(Map<String, String> payloads, long time) {
        Map<String, TaskItemSuccessResult> results = new java.util.LinkedHashMap<>();
        payloads.forEach((id, payload) -> results.put(id, new TaskItemSuccessResult(6, time, payload)));
        return results;
    }

}
