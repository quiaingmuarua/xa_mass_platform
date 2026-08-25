package com.xa.mass.kernel.score.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.kernel.KernelOperationNotImplementedException;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.Test;

class RedisTaskScoreBandCoreTest {

    @Test
    void pacerOnlyOperationsRemainExplicitGaps() {
        RedisClient redisClient = RedisClient.create(
                "redis://127.0.0.1:1"
        );
        try {
            RedisTaskScoreBandCore scoreCore =
                    new RedisTaskScoreBandCore(
                            redisClient,
                            new RedisKeyspace("test_task_score_unit")
                    );

            assertOperation(
                    "count_running_capacity_tasks",
                    scoreCore::countRunningCapacityTasks
            );
            assertOperation(
                    "acquire_band_task_candidates",
                    () -> scoreCore.acquireBandTaskCandidates(
                            TaskScoreBand.RUNNING_VISIBLE,
                            1,
                            1
                    )
            );
            assertOperation(
                    "acquire_dispatch_work_tasks",
                    () -> scoreCore.acquireDispatchWorkTasks(1)
            );
            assertOperation(
                    "rewrite_same_band_time_millis",
                    () -> scoreCore.rewriteSameBandTimeMillis(
                            "task-1",
                            TaskScoreBand.RUNNING_VISIBLE,
                            1
                    )
            );
            assertOperation(
                    "park_observed_idle_task",
                    () -> scoreCore.parkObservedIdleTask("task-1", 1)
            );
            assertOperation(
                    "close_observed_score",
                    () -> scoreCore.closeObservedScore("task-1", 1, -1)
            );
        } finally {
            redisClient.shutdown();
        }
    }

    @Test
    void previewLimitIsValidatedBeforeRedisAccess() {
        RedisClient redisClient = RedisClient.create(
                "redis://127.0.0.1:1"
        );
        try {
            RedisTaskScoreBandCore scoreCore =
                    new RedisTaskScoreBandCore(
                            redisClient,
                            new RedisKeyspace("test_task_score_preview_unit")
                    );

            assertThrows(
                    IllegalArgumentException.class,
                    () -> scoreCore.previewScoreStates(0)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> scoreCore.previewScoreStates(101)
            );
        } finally {
            redisClient.shutdown();
        }
    }

    private static void assertOperation(
            String operation,
            Runnable invocation
    ) {
        KernelOperationNotImplementedException error = assertThrows(
                KernelOperationNotImplementedException.class,
                invocation::run
        );
        assertEquals("TaskScoreBandCore", error.contractName());
        assertEquals(operation, error.operationName());
    }
}
