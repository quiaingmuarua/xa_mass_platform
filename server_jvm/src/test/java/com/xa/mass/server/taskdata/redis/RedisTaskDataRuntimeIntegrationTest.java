package com.xa.mass.server.taskdata.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.server.kernelredis.KernelRedisConfiguration;
import com.xa.mass.server.kernelredis.KernelRedisProperties;
import com.xa.mass.server.taskdata.TaskDataException;
import com.xa.mass.server.taskdata.TaskDataRuntime.TaskItemAppendStatus;
import com.xa.mass.server.taskdata.TaskDataRuntime.TaskItemRecord;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("integration")
class RedisTaskDataRuntimeIntegrationTest {

    private static final long DEFAULT_ITEM_TTL_MILLIS =
            365L * 24 * 60 * 60 * 1_000;
    private static final String REDIS_URL =
            System.getenv("KERNEL_DESIGN_REDIS_URL");
    private String prefix;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> redis;
    private RedisTaskDataRuntime runtime;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(
                REDIS_URL != null && !REDIS_URL.isBlank(),
                "KERNEL_DESIGN_REDIS_URL is not configured"
        );
        prefix = "java-task-data-" + UUID.randomUUID();
        var properties = new KernelRedisProperties(
                URI.create(REDIS_URL),
                prefix
        );
        redisClient = new KernelRedisConfiguration()
                .kernelRedisClient(properties);
        connection = redisClient.connect(StringCodec.UTF8);
        redis = connection.sync();
        runtime = new RedisTaskDataRuntime(redisClient, properties);
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.closeConnection();
        }
        if (redis != null) {
            deleteKeys(redis.keys("tc:" + prefix + ":*"));
            deleteKeys(redis.keys("tr:" + prefix + ":*"));
            deleteKeys(redis.keys("wr:" + prefix + ":*"));
        }
        if (connection != null) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void taskDrivenAppendMatchesRecordAndScoreShapeWithoutResettingScore() {
        storeTask("task-1", "group-1", "TASK_DRIVEN", "3");
        long createdAt = redisNowMillis() + 10_000;
        long expiresAt = createdAt + 20_000;
        TaskItemRecord first = item(
                "message-1",
                createdAt,
                expiresAt,
                10,
                Map.of("b", 2, "a", 1),
                null
        );

        var firstResult = runtime.appendTaskItems(
                "task-1",
                List.of(first)
        );
        Double firstScore = redis.zscore(itemScoreKey("task-1"), "message-1");
        long dueMillis = createdAt - 1_000;
        long expectedScore = 10_000_000_000_000L
                + (dueMillis / 100) * 100
                + 4;

        assertThat(firstResult.get("message-1").status())
                .isEqualTo(TaskItemAppendStatus.APPENDED);
        assertThat(firstScore).isEqualTo((double) expectedScore);
        assertThat(redis.hget(itemsKey("task-1"), "message-1"))
                .isEqualTo("""
                        {"allocationRule":null,"createdAtMillis":%d,"eventCode":"telecom.phone.inspect","expireAtMillis":%d,"payload":{"a":1,"b":2},"priority":10}"""
                        .formatted(createdAt, expiresAt));

        TaskItemRecord latest = item(
                "message-1",
                createdAt + 1_000,
                expiresAt + 1_000,
                0,
                Map.of("version", 2),
                null
        );
        var latestResult = runtime.appendTaskItems(
                "task-1",
                List.of(first, latest)
        );

        assertThat(latestResult.get("message-1").status())
                .isEqualTo(TaskItemAppendStatus.APPENDED);
        assertThat(redis.zscore(itemScoreKey("task-1"), "message-1"))
                .isEqualTo(firstScore);
        assertThat(redis.hget(itemsKey("task-1"), "message-1"))
                .contains("\"version\":2")
                .contains("\"priority\":0");
    }

    @Test
    void itemDrivenAcceptsOnlyAllowedWorkerIdTargets() {
        storeTask("task-1", "group-1", "ITEM_DRIVEN", "1");
        storeWorkerGroup("group-1", List.of("workerId"));
        long createdAt = redisNowMillis();
        long expiresAt = createdAt + 30_000;

        var results = runtime.appendTaskItems(
                "task-1",
                List.of(
                        item(
                                "eq",
                                createdAt,
                                expiresAt,
                                5,
                                Map.of(),
                                Map.of(
                                        "workerId",
                                        Map.of("$eq", "worker-1")
                                )
                        ),
                        item(
                                "in",
                                createdAt,
                                expiresAt,
                                5,
                                Map.of(),
                                Map.of(
                                        "workerId",
                                        Map.of(
                                                "$in",
                                                List.of("worker-1", "worker-2")
                                        )
                                )
                        ),
                        item(
                                "dynamic",
                                createdAt,
                                expiresAt,
                                5,
                                Map.of(),
                                Map.of(
                                        "dynamic.load",
                                        Map.of("$gte", 1)
                                )
                        ),
                        item(
                                "missing",
                                createdAt,
                                expiresAt,
                                5,
                                Map.of(),
                                null
                        )
                )
        );

        assertThat(results).extractingByKeys("eq", "in")
                .extracting(result -> result.status())
                .containsOnly(TaskItemAppendStatus.APPENDED);
        assertThat(results).extractingByKeys("dynamic", "missing")
                .extracting(result -> result.status())
                .containsOnly(TaskItemAppendStatus.INVALID);
        assertThat(redis.hlen(itemsKey("task-1"))).isEqualTo(2);
        assertThat(redis.zcard(itemScoreKey("task-1"))).isEqualTo(2);
    }

    @Test
    void defaultExpiryAndSameBatchDuplicateUseTheLastRecord() {
        storeTask("task-1", "group-1", "TASK_DRIVEN", "1");
        long createdAt = redisNowMillis();
        TaskItemRecord first = new TaskItemRecord(
                "message-1",
                "telecom.phone.inspect",
                createdAt,
                Map.of("version", 1),
                10,
                null,
                null
        );
        TaskItemRecord latest = new TaskItemRecord(
                "message-1",
                "telecom.phone.inspect",
                createdAt,
                Map.of("version", 2),
                0,
                null,
                null
        );

        var results = runtime.appendTaskItems(
                "task-1",
                List.of(first, latest)
        );

        assertThat(results.keySet()).containsExactly("message-1");
        assertThat(results.get("message-1").status())
                .isEqualTo(TaskItemAppendStatus.APPENDED);
        assertThat(redis.hget(itemsKey("task-1"), "message-1"))
                .isEqualTo("""
                        {"allocationRule":null,"createdAtMillis":%d,"eventCode":"telecom.phone.inspect","expireAtMillis":%d,"payload":{"version":2},"priority":0}"""
                        .formatted(
                                createdAt,
                                createdAt + DEFAULT_ITEM_TTL_MILLIS
                        ));
        assertThat(redis.zscore(itemScoreKey("task-1"), "message-1"))
                .isEqualTo(
                        10_000_000_000_000d
                                + (createdAt / 100) * 100d
                                + 2d
                );
    }

    @Test
    void recordWriteSurvivesScoreFailureAndRetryConverges() {
        storeTask("task-1", "group-1", "TASK_DRIVEN", "0");
        long createdAt = redisNowMillis();
        TaskItemRecord item = item(
                "message-1",
                createdAt,
                createdAt + 30_000,
                5,
                Map.of("attempt", 1),
                null
        );
        redis.set(itemScoreKey("task-1"), "wrong-type");

        var failed = runtime.appendTaskItems("task-1", List.of(item));

        assertThat(failed.get("message-1").status())
                .isEqualTo(TaskItemAppendStatus.RETRYABLE);
        assertThat(redis.hexists(itemsKey("task-1"), "message-1")).isTrue();

        redis.del(itemScoreKey("task-1"));
        var retried = runtime.appendTaskItems("task-1", List.of(item));

        assertThat(retried.get("message-1").status())
                .isEqualTo(TaskItemAppendStatus.APPENDED);
        assertThat(redis.zscore(itemScoreKey("task-1"), "message-1"))
                .isNotNull();
    }

    @Test
    void missingTaskAndSuccessResultQueriesKeepDistinctSemantics() {
        long createdAt = redisNowMillis();
        var missingAppend = runtime.appendTaskItems(
                "missing",
                List.of(item(
                        "message-1",
                        createdAt,
                        createdAt + 30_000,
                        5,
                        Map.of(),
                        null
                ))
        );
        assertThat(missingAppend.get("message-1").status())
                .isEqualTo(TaskItemAppendStatus.NOT_FOUND);

        storeTask("task-1", "group-1", "TASK_DRIVEN", "3");
        redis.hset(
                resultsKey("task-1"),
                "message-1",
                "{\"valid\":true}"
        );
        Map<String, String> loaded = runtime.loadTaskItemSuccessResults(
                "task-1",
                List.of("message-1", "message-2", "message-1")
        );

        assertThat(loaded.keySet()).containsExactly(
                "message-1",
                "message-2"
        );
        assertThat(loaded.get("message-1")).isEqualTo("{\"valid\":true}");
        assertThat(loaded).containsEntry("message-2", null);
        assertThatThrownBy(() -> runtime.loadTaskItemSuccessResults(
                "missing",
                List.of("message-1")
        )).isInstanceOf(TaskDataException.class)
                .satisfies(error -> assertThat(
                        ((TaskDataException) error).kind()
                ).isEqualTo(TaskDataException.Kind.NOT_FOUND));
    }

    @Test
    void invalidAndExpiredItemsDoNotWriteTruth() {
        storeTask("task-1", "group-1", "TASK_DRIVEN", "3");
        long now = redisNowMillis();

        var results = runtime.appendTaskItems(
                "task-1",
                List.of(
                        item(
                                "expired",
                                now - 2_000,
                                now - 1_000,
                                5,
                                Map.of(),
                                null
                        ),
                        item(
                                "rule",
                                now,
                                now + 30_000,
                                5,
                                Map.of(),
                                Map.of(
                                        "workerId",
                                        Map.of("$eq", "worker-1")
                                )
                        )
                )
        );

        assertThat(results.values())
                .extracting(result -> result.status())
                .containsOnly(TaskItemAppendStatus.INVALID);
        assertThat(redis.exists(itemsKey("task-1"))).isZero();
        assertThat(redis.exists(itemScoreKey("task-1"))).isZero();
    }

    @Test
    void corruptTaskDeclarationFailsClosedAsUnavailable() {
        redis.hset(
                taskKey("task-1"),
                Map.of(
                        "workerGroupId", "group-1",
                        "taskType", "TASK_DRIVEN",
                        "allocationRuleJson", "{}",
                        "configJson", "{\"maxRetryTimes\":\"bad\"}",
                        "emptyCloseAtMillis", "0"
                )
        );
        long createdAt = redisNowMillis();

        assertThatThrownBy(() -> runtime.appendTaskItems(
                "task-1",
                List.of(item(
                        "message-1",
                        createdAt,
                        createdAt + 30_000,
                        5,
                        Map.of(),
                        null
                ))
        )).isInstanceOf(TaskDataException.class)
                .satisfies(error -> assertThat(
                        ((TaskDataException) error).kind()
                ).isEqualTo(TaskDataException.Kind.UNAVAILABLE));
    }

    private void storeTask(
            String taskId,
            String workerGroupId,
            String taskType,
            String maxRetryTimes
    ) {
        redis.hset(
                taskKey(taskId),
                Map.of(
                        "workerGroupId", workerGroupId,
                        "taskType", taskType,
                        "allocationRuleJson", taskType.equals("TASK_DRIVEN")
                                ? "{}"
                                : "null",
                        "configJson", """
                                {"maxRetryTimes":"%s","maximumCandidateWorkers":"1","priority":"0"}"""
                                .formatted(maxRetryTimes),
                        "emptyCloseAtMillis", "0"
                )
        );
    }

    private void storeWorkerGroup(
            String workerGroupId,
            List<String> itemAllocationFields
    ) {
        String fields = JsonMapper.builder().build()
                .valueToTree(itemAllocationFields)
                .toString();
        redis.hset(
                groupsKey(),
                workerGroupId,
                """
                        {"attributes":{},"eventCodes":[],"itemAllocationFields":%s,"workerGroupId":"%s"}"""
                        .formatted(fields, workerGroupId)
        );
    }

    private TaskItemRecord item(
            String messageId,
            long createdAt,
            long expireAt,
            int priority,
            Map<String, Object> payload,
            Map<String, Object> allocationRule
    ) {
        return new TaskItemRecord(
                messageId,
                "telecom.phone.inspect",
                createdAt,
                payload,
                priority,
                expireAt,
                allocationRule
        );
    }

    private long redisNowMillis() {
        List<String> parts = redis.time();
        return Long.parseLong(parts.get(0)) * 1_000
                + Long.parseLong(parts.get(1)) / 1_000;
    }

    private String taskKey(String taskId) {
        return "tc:" + prefix + ":task:" + taskId;
    }

    private String groupsKey() {
        return "wr:" + prefix + ":groups";
    }

    private String itemsKey(String taskId) {
        return "tr:" + prefix + ":task:" + taskId + ":items";
    }

    private String itemScoreKey(String taskId) {
        return "tr:" + prefix + ":task:" + taskId + ":item-score";
    }

    private String resultsKey(String taskId) {
        return "tr:" + prefix + ":task:" + taskId + ":results";
    }

    private void deleteKeys(java.util.Collection<String> keys) {
        if (!keys.isEmpty()) {
            redis.del(keys.toArray(String[]::new));
        }
    }
}
