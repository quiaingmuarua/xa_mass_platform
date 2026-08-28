package com.xa.mass.kernel.score.redis;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.kernel.redis.RedisKeyspace;
import io.lettuce.core.RedisClient;
import java.util.LinkedHashMap;
import java.util.Map;
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
                    Map.of(),
                    scoreCore.acquireSchedulingTasks(0)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> scoreCore.acquireSchedulingTasks(-1)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> scoreCore.acquireSchedulingTasks(101)
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

    @Test
    void initializationBatchBoundsAreValidatedBeforeRedisAccess() {
        RedisClient redisClient = RedisClient.create(
                "redis://127.0.0.1:1"
        );
        try {
            RedisTaskScoreBandCore scoreCore =
                    new RedisTaskScoreBandCore(
                            redisClient,
                            new RedisKeyspace("test_task_initial_batch_unit")
                    );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> scoreCore.promoteObservedInitialTasks(null)
            );
            org.junit.jupiter.api.Assertions.assertEquals(
                    Map.of(),
                    scoreCore.promoteObservedInitialTasks(Map.of())
            );
            Map<String, Long> tooMany = new LinkedHashMap<>();
            for (int index = 0; index < 101; index++) {
                tooMany.put("task-" + index, 1L);
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> scoreCore.promoteObservedInitialTasks(tooMany)
            );
        } finally {
            redisClient.shutdown();
        }
    }

    @Test
    void initialFilteringDoesNotAccessRedis() {
        RedisClient redisClient = RedisClient.create(
                "redis://127.0.0.1:1"
        );
        try {
            RedisTaskScoreBandCore scoreCore =
                    new RedisTaskScoreBandCore(
                            redisClient,
                            new RedisKeyspace("test_task_initial_filter")
                    );
            long runningBase = com.xa.mass.kernel.score.TaskScoreBandCore
                    .RUNNING_VISIBLE_TAG
                    * com.xa.mass.kernel.score.TaskScoreBandCore
                    .DEFAULT_TAG_FACTOR;
            long initial = runningBase
                    + com.xa.mass.kernel.score.TaskScoreBandCore
                    .INITIAL_TIME_SLOT
                    * com.xa.mass.kernel.score.TaskScoreBandCore
                    .SUFFIX_FACTOR
                    + 99;
            long normal = runningBase
                    + com.xa.mass.kernel.score.TaskScoreBandCore
                    .NORMAL_TIME_SLOT_MIN
                    * com.xa.mass.kernel.score.TaskScoreBandCore
                    .SUFFIX_FACTOR;

            org.junit.jupiter.api.Assertions.assertEquals(
                    Map.of("task-initial", initial),
                    scoreCore.filterInitialTaskScores(new LinkedHashMap<>(
                            Map.of(
                                    "task-initial", initial,
                                    "task-normal", normal
                            )
                    ))
            );
        } finally {
            redisClient.shutdown();
        }
    }
}
