package com.xa.mass.server.kernelbinding;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.score.redis.RedisTaskScoreBandCore;
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
import com.xa.mass.server.api.v1.model.TaskCreateRequest;
import com.xa.mass.server.testsupport.RedisTestScope;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.List;
import java.util.Map;
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
        runtime = new RedisTaskRuntime(redisClient, scoreCore, keyspace);
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

        redis.hset(
                keyspace.base() + ":task:task-1:results",
                "message-1",
                "{\"valid\":true}"
        );
        var loaded = runtime.loadTaskItemSuccessResults(
                "task-1",
                List.of("message-1", "missing")
        );
        assertThat(loaded.get("message-1"))
                .isEqualTo("{\"valid\":true}");
        assertThat(loaded).containsEntry("missing", null);
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
                TaskScoreBandCore.TaskScoreBand.ADMISSION_VISIBLE
        );
        assertThat(approved.suffix()).isEqualTo(7);

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
    void genericRewritePreservesSuffixAndRejectsBackwardBandMovement() {
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
        long nextTime = releasedState.get("rewrite-task").timeMillis()
                + TaskScoreBandCore.SLOT_MILLIS;

        var sameBand = scoreCore.rewriteScore(
                "rewrite-task",
                TaskScoreBandCore.TaskScoreBand.PRE_REVIEW,
                nextTime,
                null,
                null
        );
        assertThat(sameBand.status()).isEqualTo(
                TaskScoreBandCore.TaskScoreTransitionStatus.TRANSITIONED
        );
        assertThat(scoreCore.getScoreStates(List.of("rewrite-task"))
                .get("rewrite-task").suffix()).isEqualTo(8);

        var admission = scoreCore.rewriteScore(
                "rewrite-task",
                TaskScoreBandCore.TaskScoreBand.PRE_REVIEW,
                nextTime + TaskScoreBandCore.SLOT_MILLIS,
                TaskScoreBandCore.TaskScoreBand.ADMISSION_VISIBLE,
                4
        );
        assertThat(admission.status()).isEqualTo(
                TaskScoreBandCore.TaskScoreTransitionStatus.TRANSITIONED
        );
        assertThat(scoreCore.rewriteScore(
                "rewrite-task",
                TaskScoreBandCore.TaskScoreBand.ADMISSION_VISIBLE,
                nextTime + 2 * TaskScoreBandCore.SLOT_MILLIS,
                TaskScoreBandCore.TaskScoreBand.PRE_REVIEW,
                1
        ).status()).isEqualTo(
                TaskScoreBandCore.TaskScoreTransitionStatus.INVALID
        );
        assertThat(scoreCore.releaseObservedScoreHold(
                "rewrite-task",
                initialized.score()
        ).status()).isEqualTo(
                TaskScoreBandCore.TaskScoreTransitionStatus.STALE
        );
    }

    @Test
    void publicFiniteTaskControlUsesJavaOwnersWithoutPythonHttp() {
        TaskControlController controller = new TaskControlController(
                runtime,
                lifecycle,
                catalog
        );
        var created = controller.createTask(new TaskCreateRequest(
                "public-task",
                "phone-tools",
                Map.of(),
                config(2)
        ));

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(controller.approveTask("public-task")
                .getStatusCode().value()).isEqualTo(200);
        assertThat(controller.closeTask("public-task")
                .getStatusCode().value()).isEqualTo(200);
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
