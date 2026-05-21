package com.xa.mass.runtime.redis;

import com.xa.mass.runtime.api.BarrierClaim;
import com.xa.mass.runtime.api.BarrierClaimStatus;
import com.xa.mass.runtime.api.BarrierMarkStatus;
import com.xa.mass.runtime.api.CommitResultStatus;
import com.xa.mass.runtime.api.TaskResultFinalDraft;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RedisTaskResultRuntimeTest {

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private StatefulRedisConnection<String, String> observerConnection;
    private RedisCommands<String, String> commands;
    private RedisTaskResultRuntime runtime;
    private RedisTaskResultKeyspace keyspace;

    @BeforeEach
    void setUp() {
        redisClient = RedisRuntimeTestSupport.createClientOrSkip("result runtime test");
        connection = redisClient.connect();
        observerConnection = redisClient.connect();
        commands = observerConnection.sync();
        keyspace = new RedisTaskResultKeyspace(RedisRuntimeTestSupport.namespace("result-runtime"));
        runtime = new RedisTaskResultRuntime(connection, keyspace, Instant::now);
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.shutdown();
        }
        RedisRuntimeTestSupport.cleanupNamespace(commands, keyspace == null ? null : keyspace.namespace());
        if (connection != null && connection.isOpen()) {
            connection.close();
        }
        if (observerConnection != null && observerConnection.isOpen()) {
            observerConnection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void fullyConvergedBarriersAreRemovedButVisibleResultRemains() {
        assertThat(runtime.commitVisibleFinal(finalDraft("task-1", "msg-1", "SUCCESS")).status())
                .isEqualTo(CommitResultStatus.COMMITTED);
        long seq = runtime.getVisibleByMessageId("task-1", "msg-1").orElseThrow().seq();

        BarrierClaim attemptClaim = runtime.claimAttemptClosedPublish("task-1", "msg-1", seq);
        assertThat(attemptClaim.status()).isEqualTo(BarrierClaimStatus.CLAIMED);
        assertThat(runtime.markAttemptClosedPublished("task-1", "msg-1", seq, attemptClaim.claimToken()).status())
                .isEqualTo(BarrierMarkStatus.MARKED);
        assertThat(commands.exists(
                keyspace.attemptClosedBarrier("task-1", "msg-1", seq),
                keyspace.logicalFinalBarrier("task-1", "msg-1", seq),
                keyspace.progressBarrier("task-1", "msg-1", seq)
        )).isEqualTo(1L);

        BarrierClaim logicalClaim = runtime.claimLogicalFinalPublish("task-1", "msg-1", seq);
        assertThat(logicalClaim.status()).isEqualTo(BarrierClaimStatus.CLAIMED);
        assertThat(runtime.markLogicalFinalPublished("task-1", "msg-1", seq, logicalClaim.claimToken()).status())
                .isEqualTo(BarrierMarkStatus.MARKED);
        assertThat(commands.exists(
                keyspace.attemptClosedBarrier("task-1", "msg-1", seq),
                keyspace.logicalFinalBarrier("task-1", "msg-1", seq),
                keyspace.progressBarrier("task-1", "msg-1", seq)
        )).isEqualTo(2L);

        BarrierClaim progressClaim = runtime.claimProgressApply("task-1", "msg-1", seq);
        assertThat(progressClaim.status()).isEqualTo(BarrierClaimStatus.CLAIMED);
        assertThat(runtime.markProgressApplied("task-1", "msg-1", seq, progressClaim.claimToken()).status())
                .isEqualTo(BarrierMarkStatus.MARKED);

        assertThat(commands.exists(
                keyspace.attemptClosedBarrier("task-1", "msg-1", seq),
                keyspace.logicalFinalBarrier("task-1", "msg-1", seq),
                keyspace.progressBarrier("task-1", "msg-1", seq)
        )).isZero();
        assertThat(commands.zcard(keyspace.attemptClosedPendingZset())).isZero();
        assertThat(commands.zcard(keyspace.logicalFinalPendingZset())).isZero();
        assertThat(commands.zcard(keyspace.progressPendingZset())).isZero();
        assertThat(commands.exists(keyspace.taskVisibleRow("task-1", "msg-1"))).isEqualTo(1L);
        assertThat(runtime.getVisibleByMessageId("task-1", "msg-1")).get()
                .satisfies(row -> {
                    assertThat(row.attemptClosedPublished()).isTrue();
                    assertThat(row.logicalFinalPublished()).isTrue();
                    assertThat(row.progressApplied()).isTrue();
                });
        assertThat(runtime.claimAttemptClosedPublish("task-1", "msg-1", seq).status())
                .isEqualTo(BarrierClaimStatus.ALREADY_DONE);
    }

    @Test
    void namespacesDoNotShareVisibleResultRowsOrCleanup() {
        RedisTaskResultKeyspace otherKeyspace = new RedisTaskResultKeyspace(RedisRuntimeTestSupport.namespace("result-runtime-isolated"));
        StatefulRedisConnection<String, String> otherConnection = redisClient.connect();
        RedisTaskResultRuntime otherRuntime = new RedisTaskResultRuntime(otherConnection, otherKeyspace, Instant::now);
        try {
            assertThat(runtime.commitVisibleFinal(finalDraft("shared-task", "msg-1", "SUCCESS")).status())
                    .isEqualTo(CommitResultStatus.COMMITTED);
            assertThat(otherRuntime.commitVisibleFinal(finalDraft("shared-task", "msg-1", "FAILED")).status())
                    .isEqualTo(CommitResultStatus.COMMITTED);

            assertThat(runtime.getVisibleByMessageId("shared-task", "msg-1")).get()
                    .satisfies(row -> assertThat(row.status()).isEqualTo("SUCCESS"));
            assertThat(otherRuntime.getVisibleByMessageId("shared-task", "msg-1")).get()
                    .satisfies(row -> assertThat(row.status()).isEqualTo("FAILED"));

            RedisRuntimeTestSupport.cleanupNamespace(commands, keyspace.namespace());

            assertThat(commands.exists(keyspace.taskVisibleRow("shared-task", "msg-1"))).isZero();
            assertThat(commands.exists(otherKeyspace.taskVisibleRow("shared-task", "msg-1"))).isEqualTo(1L);
            assertThat(otherRuntime.getVisibleByMessageId("shared-task", "msg-1")).isPresent();
        } finally {
            otherRuntime.shutdown();
            if (otherConnection.isOpen()) {
                otherConnection.close();
            }
            RedisRuntimeTestSupport.cleanupNamespace(commands, otherKeyspace.namespace());
        }
    }

    private TaskResultFinalDraft finalDraft(String taskId, String messageId, String status) {
        return TaskResultFinalDraft.workerLevel(
                taskId,
                messageId,
                "demo.event",
                status,
                "SUCCESS".equals(status) ? "BUSINESS_SUCCESS" : "RETRY_EXHAUSTED",
                0,
                3,
                "worker-1",
                "batch-1",
                "attempt-1",
                "payload-ref",
                Instant.parse("2026-05-13T00:00:00Z"),
                Instant.parse("2026-05-13T00:00:01Z"),
                Instant.parse("2026-05-13T00:00:02Z"),
                Instant.parse("2026-05-13T00:00:03Z"),
                Instant.parse("2026-05-13T00:00:03Z"),
                "FAILED".equals(status) ? "ERR" : null,
                "FAILED".equals(status) ? "failed" : null,
                Map.of("status", status),
                "stage-" + messageId
        );
    }

}
