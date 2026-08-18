package com.xa.mass.server.kernelbinding;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendStatus;
import com.xa.mass.kernel.task.redis.RedisTaskResourceCatalog;
import com.xa.mass.kernel.task.redis.RedisTaskRuntime;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("redis-owner")
class RedisTaskOwnerRuntimeIntegrationTest {

    private String prefix;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> redis;
    private RedisTaskRuntime runtime;
    private RedisTaskResourceCatalog catalog;

    @BeforeEach
    void setUp() {
        prefix = "java-task-owner-" + UUID.randomUUID();
        redisClient = RedisClient.create(REDIS_URL);
        connection = redisClient.connect(StringCodec.UTF8);
        redis = connection.sync();
        runtime = new RedisTaskRuntime(redisClient, prefix);
        catalog = new RedisTaskResourceCatalog(redisClient, prefix);
    }

    @AfterEach
    void tearDown() {
        if (redis != null) {
            var keys = redis.keys("*:" + prefix + ":*");
            if (!keys.isEmpty()) {
                redis.del(keys.toArray(String[]::new));
            }
        }
        if (runtime != null) {
            runtime.close();
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
        storeTask("task-1", "TASK_DRIVEN");
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
                "tr:" + prefix + ":task:task-1:items",
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
                "tr:" + prefix + ":task:task-1:item-score",
                "message-1"
        );
        long expected = TaskItemScoreBandCore.ACTIVE_TAG
                * TaskItemScoreBandCore.TAG_FACTOR
                + (createdAt / TaskItemScoreBandCore.SLOT_MILLIS)
                * TaskItemScoreBandCore.SUFFIX_FACTOR
                + 4;
        assertThat((long) score).isEqualTo(expected);

        redis.hset(
                "tr:" + prefix + ":task:task-1:results",
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
        storeTask("task-1", "ITEM_DRIVEN");

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
        storeTask("task-1", "ITEM_DRIVEN");
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
                "tr:" + prefix + ":task:task-1:items",
                "message-invalid"
        )).contains("\"$like\":\"worker-*\"");
        assertThat(redis.zscore(
                "tr:" + prefix + ":task:task-1:item-score",
                "message-invalid"
        )).isNotNull();
    }

    private void storeTask(String taskId, String taskType) {
        redis.hset(
                "tc:" + prefix + ":task:" + taskId,
                Map.of(
                        "workerGroupId", "phone-tools",
                        "taskType", taskType,
                        "allocationRuleJson",
                        "TASK_DRIVEN".equals(taskType) ? "{}" : "null",
                        "configJson",
                        "{\"maxRetryTimes\":\"3\","
                                + "\"maximumCandidateWorkers\":\"1\","
                                + "\"priority\":\"0\"}",
                        "emptyCloseAtMillis", "0"
                )
        );
    }

    private long redisTimeMillis() {
        List<String> parts = redis.time();
        return Long.parseLong(parts.get(0)) * 1_000
                + Long.parseLong(parts.get(1)) / 1_000;
    }
}
