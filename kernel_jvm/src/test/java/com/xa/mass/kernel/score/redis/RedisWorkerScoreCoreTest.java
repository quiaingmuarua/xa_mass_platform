package com.xa.mass.kernel.score.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreDelayTarget;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import io.lettuce.core.RedisClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedisWorkerScoreCoreTest {

    @Test
    void recoveryTransitionsValidateInvalidInputBeforeRedisAccess() {
        RedisClient redisClient = RedisClient.create(
                "redis://127.0.0.1:1"
        );
        try {
            RedisWorkerScoreCore scoreCore = new RedisWorkerScoreCore(
                    redisClient,
                    new RedisKeyspace("test_worker_score_unit")
            );

            assertEquals(
                    WorkerScoreTransitionStatus.INVALID,
                    scoreCore.toggleCurrentPolarity(
                            "group-1", "worker-1", 0L
                    ).status()
            );
            assertEquals(
                    WorkerScoreTransitionStatus.INVALID,
                    scoreCore.parkObservedRecoveryScore(
                            "group-1", "worker-1", -200L, 0
                    ).status()
            );
            assertEquals(
                    WorkerScoreTransitionStatus.INVALID,
                    scoreCore.deferObservedToRecovery(
                            "group-1",
                            Map.of("worker-1", new WorkerScoreDelayTarget(0L, 1_000L, 0))
                    ).get("worker-1").status()
            );
            assertEquals(
                    WorkerScoreTransitionStatus.INVALID,
                    scoreCore.deferObservedToRecovery(
                            "group-1",
                            Map.of("worker-1", new WorkerScoreDelayTarget(200L, 1_000L, 100))
                    ).get("worker-1").status()
            );
            assertEquals(
                    WorkerScoreTransitionStatus.INVALID,
                    scoreCore.rewriteCurrentPolarityWithinTimeFence(
                            "group-1",
                            Map.of("worker-1", 0L),
                            WorkerScorePolarity.HOT_ACQUIRE, true
                    ).get("worker-1").status()
            );
        } finally {
            redisClient.shutdown();
        }
    }

    @Test
    void recheckTargetsValidateDelayAndBatchBoundsBeforeRedisAccess() {
        RedisClient redisClient = RedisClient.create("redis://127.0.0.1:1");
        try (var scoreCore = new RedisWorkerScoreCore(redisClient,
                new RedisKeyspace("test_worker_score_unit"))) {
            for (long delay : new long[]{0, -1, Long.MAX_VALUE, WorkerScoreCore.PAUSE_TIME_MILLIS}) {
                assertEquals(WorkerScoreTransitionStatus.INVALID,
                        scoreCore.deferObservedToRecovery("g",
                                Map.of("w", new WorkerScoreDelayTarget(20_000L, delay, 0)))
                                .get("w").status());
                assertEquals(WorkerScoreTransitionStatus.INVALID,
                        scoreCore.deferObservedToRecovery("g",
                                Map.of("w", new WorkerScoreDelayTarget(-20_000L, delay, 1)))
                                .get("w").status());
            }
            assertEquals(WorkerScoreTransitionStatus.INVALID,
                    scoreCore.deferObservedToRecovery("g",
                            Map.of("cold", new WorkerScoreDelayTarget(-200L, 1_000, 1)))
                            .get("cold").status());
            assertEquals(Map.of(), scoreCore.deferObservedToRecovery("g", Map.of()));
            var tooMany = new java.util.LinkedHashMap<String, WorkerScoreDelayTarget>();
            for (int i = 0; i < 101; i++) tooMany.put("w" + i, new WorkerScoreDelayTarget(-20_000, 1_000, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> scoreCore.deferObservedToRecovery("g", tooMany));
            assertThrows(IllegalArgumentException.class,
                    () -> scoreCore.deferObservedToRecovery("g", null));
        } finally {
            redisClient.shutdown();
        }
    }

    @Test
    void dirtyInvalidationValidatesBoundsBeforeRedisAccess() {
        RedisClient redisClient = RedisClient.create(
                "redis://127.0.0.1:1"
        );
        try {
            RedisWorkerScoreCore scoreCore = new RedisWorkerScoreCore(
                    redisClient,
                    new RedisKeyspace("test_worker_score_unit")
            );
            for (List<String> ids : List.of(
                    List.<String>of(), List.of("w", "w"), List.of(" "),
                    java.util.stream.IntStream.range(0, 101).mapToObj(i -> "w" + i).toList())) {
                assertThrows(IllegalArgumentException.class,
                        () -> scoreCore.markCurrentLeasesDirty("group-1", ids));
            }
            assertThrows(IllegalArgumentException.class,
                    () -> scoreCore.markCurrentLeasesDirty("group-1", null));
            assertThrows(IllegalArgumentException.class,
                    () -> scoreCore.markCurrentLeasesDirty(" ", List.of("w")));
        } finally {
            redisClient.shutdown();
        }
    }

    @Test
    void activeConfirmationValidatesInputsBeforeRedisAccess() {
        RedisClient client = RedisClient.create("redis://127.0.0.1:1");
        try (var scores = new RedisWorkerScoreCore(client, new RedisKeyspace("test_worker_score_unit"))) {
            assertEquals(Map.of(), scores.confirmActiveHotScoreLeases("g", Map.of(), 1_000));
            assertEquals(WorkerScoreTransitionStatus.INVALID,
                    scores.confirmActiveHotScoreLeases("g", Map.of("w", 200L), -1).get("w").status());
            assertThrows(IllegalArgumentException.class,
                    () -> scores.confirmActiveHotScoreLeases("g", Map.of(" ", 200L), 1_000));
            assertThrows(IllegalArgumentException.class,
                    () -> scores.confirmActiveHotScoreLeases("g", null, 1_000));
            for (int rank : new int[]{-1, 100}) {
                assertEquals(WorkerScoreTransitionStatus.INVALID,
                        scores.deferObservedToRecovery("g", Map.of("w",
                                new WorkerScoreDelayTarget(20_000, 1_000, rank))).get("w").status());
            }
        } finally {
            client.shutdown();
        }
    }
}
