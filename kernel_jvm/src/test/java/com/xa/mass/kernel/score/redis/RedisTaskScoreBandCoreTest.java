package com.xa.mass.kernel.score.redis;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.kernel.redis.RedisKeyspace;
import io.lettuce.core.RedisClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class RedisTaskScoreBandCoreTest {

    @Test
    void zeroLimitCandidateReadsReturnWithoutRedisAccess() {
        RedisClient redisClient = RedisClient.create(
                "redis://127.0.0.1:1"
        );
        try {
            RedisTaskScoreBandCore scoreCore =
                    new RedisTaskScoreBandCore(
                            redisClient,
                            new RedisKeyspace("test_task_score_unit")
                    );

            org.junit.jupiter.api.Assertions.assertEquals(
                    new com.xa.mass.kernel.score.TaskScoreBandCore
                            .TaskScoreScanPage(0, List.of()),
                    scoreCore.acquireDispatchWorkTasks(0)
            );
            org.junit.jupiter.api.Assertions.assertEquals(
                    List.of(),
                    scoreCore.acquireInitialRunningTasks(0)
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
}
