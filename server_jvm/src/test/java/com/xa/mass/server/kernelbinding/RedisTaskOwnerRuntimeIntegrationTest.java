package com.xa.mass.server.kernelbinding;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendStatus;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import com.xa.mass.kernel.task.redis.RedisTaskResourceCatalog;
import com.xa.mass.kernel.task.redis.RedisTaskRuntime;
import com.xa.mass.server.api.v1.TaskControlController;
import com.xa.mass.server.taskdata.TaskCreationService;
import com.xa.mass.server.taskdata.TaskLifecycleService;
import com.xa.mass.server.testsupport.RedisTestScope;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.ArrayList;
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
    void appendAndSuccessLoadMatchTaskOwnerShape() {
        long createdAt = redisTimeMillis();
        storeTask("task-1", "PRECOMPUTED_TASK_RULE");
        TaskItem item = new TaskItem(
                "message-1",
                "telecom.phone.inspect",
                createdAt,
                Map.of("phoneNumber", "+14155552671"),
                0,
                createdAt + 60_000,
                null
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
                "{\"allocationRule\":null,"
                        + "\"createdAtMillis\":" + createdAt + ","
                        + "\"eventCode\":\"telecom.phone.inspect\","
                        + "\"expireAtMillis\":" + (createdAt + 60_000) + ","
                        + "\"payload\":{\"phoneNumber\":\"+14155552671\"},"
                        + "\"priority\":0}"
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

        runtime.storeTaskItemSuccessResults(
                "task-1",
                Map.of("message-1", "{\"valid\":true}")
        );
        assertThat(itemScoreCore.promoteItemOutcomes(
                "task-1",
                List.of("message-1"),
                TaskItemScoreBandCore.TaskItemScoreBand.FINAL_SUCCESS,
                redisTimeMillis()
        ).get("message-1").status()).isEqualTo(
                TaskItemScoreBandCore.TaskItemScoreTransitionStatus
                        .TRANSITIONED
        );
        long finalScore = redis.zscore(
                keyspace.base() + ":task:task-1:item_score",
                "message-1"
        ).longValue();
        assertThat(finalScore / TaskItemScoreBandCore.TAG_FACTOR)
                .isEqualTo(TaskItemScoreBandCore.FINAL_SUCCESS_TAG);
        var loaded = runtime.loadTaskItemSuccessResults(
                "task-1",
                List.of("message-1", "missing")
        );
        assertThat(loaded.get("message-1"))
                .isEqualTo("{\"valid\":true}");
        assertThat(loaded).containsEntry("missing", null);

        var page = runtime.scanTaskItemSuccessResults(
                "task-1",
                "0",
                1000
        );
        assertThat(page.nextCursor()).isEqualTo("0");
        assertThat(page.results()).containsExactly(
                Map.entry("message-1", "{\"valid\":true}")
        );
    }

    @Test
    void catalogReadsTheCanonicalDescriptorAndMissingAppendIsNarrow() {
        storeTask("task-1", "DIRECT_ITEM_RULE");

        var descriptor = catalog.loadTaskAllocationDescriptors(
                List.of("task-1", "missing")
        );
        assertThat(descriptor.get("task-1").workerGroupId())
                .isEqualTo("phone-tools");
        assertThat(descriptor.get("task-1").allocationRule()).isNull();
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
                        Map.of("workerId", Map.of("$eq", "worker-1"))
                ))
        ).get("message-1").status()).isEqualTo(
                TaskItemAppendStatus.NOT_FOUND
        );
    }

    @Test
    void allocationRuleIsPersistedWithoutJvmDslInterpretation() {
        long createdAt = redisTimeMillis();
        storeTask("task-1", "DIRECT_ITEM_RULE");
        TaskItem item = new TaskItem(
                "message-invalid",
                "event",
                createdAt,
                Map.of(),
                5,
                createdAt + 60_000,
                Map.of("workerId", Map.of("$like", "worker-*"))
        );

        assertThat(runtime.appendItems(
                "task-1",
                List.of(item)
        ).get("message-invalid").status()).isEqualTo(
                TaskItemAppendStatus.APPENDED
        );
        assertThat(redis.hget(
                keyspace.base() + ":task:task-1:items",
                "message-invalid"
        )).contains("\"$like\":\"worker-*\"");
        assertThat(redis.zscore(
                keyspace.base() + ":task:task-1:item_score",
                "message-invalid"
        )).isNotNull();
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
                "workerAllocationMechanism", "DIRECT_ITEM_RULE",
                "idleDisposition", "PARK_WHEN_IDLE",
                "allocationRuleJson", "null",
                "configJson", "{\"maxRetryTimes\":\"3\","
                        + "\"maximumCandidateWorkers\":\"1\","
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
                Map.of("workerId", Map.of("$eq", "worker-1"))
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
    void createRecoversScoreOnlyInterruptionAndRejectsInvalidDsl() {
        assertThat(scoreCore.initializeScore("score-only", 1, 3_000)
                .status()).isEqualTo(
                        TaskScoreBandCore.TaskScoreTransitionStatus
                                .TRANSITIONED
                );
        assertThat(runtime.createTask(descriptor("score-only", 3)).status())
                .isEqualTo(TaskCreationStatus.CREATED);
        assertThat(runtime.createTask(descriptor("score-only", 3)).status())
                .isEqualTo(TaskCreationStatus.CONFLICT);

        TaskDescriptor invalidRule = new TaskDescriptor(
                "invalid-rule",
                "phone-tools",
                WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE,
                TaskIdleDisposition.CLOSE_WHEN_IDLE,
                Map.of("worker.region", Map.of("$like", "cn-*")),
                config(3)
        );
        assertThat(runtime.createTask(invalidRule).status())
                .isEqualTo(TaskCreationStatus.INVALID);
        assertThat(redis.exists(
                keyspace.base() + ":task:invalid-rule:descriptor"
        )).isZero();
        assertThat(redis.zscore(
                keyspace.base() + ":task:score",
                "invalid-rule"
        )).isNull();
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
                .containsEntry("workerAllocationMechanism", "DIRECT_ITEM_RULE")
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
                Map.of("workerId", Map.of("$eq", "worker-1"))
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
                nowSlot,
                0
        ), "running-current");
        redis.zadd(scoreKey, taskScore(
                TaskScoreBandCore.RUNNING_VISIBLE_TAG,
                nowSlot + 100,
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
        for (int index = 1; index <= 101; index++) {
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
        var bounded = scoreCore.previewScoreStates(100);
        assertThat(bounded).hasSize(100);
        assertThat(bounded.getFirst().taskId()).isEqualTo("task-101");
        assertThat(bounded.getLast().taskId()).isEqualTo("task-2");

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
                Map.of(),
                config(2)
        ));

        assertThat(created.status()).isEqualTo(TaskCreationStatus.CREATED);
        assertThat(controller.approveTask("public-task").status()
                .wireValue()).isEqualTo("approved");
        assertThat(controller.closeTask("public-task").status()
                .wireValue()).isEqualTo("closed");
    }

    private TaskDescriptor descriptor(String taskId, int priority) {
        return new TaskDescriptor(
                taskId,
                "phone-tools",
                WorkerAllocationMechanism.DIRECT_ITEM_RULE,
                TaskIdleDisposition.PARK_WHEN_IDLE,
                null,
                config(priority)
        );
    }

    private static Map<String, String> config(int priority) {
        return Map.of(
                "priority", Integer.toString(priority),
                "maximumCandidateWorkers", "1",
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
                        "allocationRuleJson",
                        "PRECOMPUTED_TASK_RULE".equals(allocationMechanism)
                                ? "{}" : "null",
                        "configJson",
                        "{\"maxRetryTimes\":\"3\","
                                + "\"maximumCandidateWorkers\":\"1\","
                                + "\"priority\":\"0\"}"
                )
        );
    }

    private long redisTimeMillis() {
        List<String> parts = redis.time();
        return Long.parseLong(parts.get(0)) * 1_000
                + Long.parseLong(parts.get(1)) / 1_000;
    }
}
