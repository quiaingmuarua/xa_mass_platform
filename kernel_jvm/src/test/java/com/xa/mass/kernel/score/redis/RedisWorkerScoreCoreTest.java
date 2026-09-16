package com.xa.mass.kernel.score.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.score.WorkerScoreCore;
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
                            "group-1", "worker-1", 2L
                    ).status()
            );
            assertEquals(
                    WorkerScoreTransitionStatus.INVALID,
                    scoreCore.deferObservedToRecovery("group-1", Map.of("worker-1", 0L), 1_000L).get("worker-1").status()
            );
            assertEquals(
                    WorkerScoreTransitionStatus.INVALID,
                    scoreCore.deferObservedToRecovery("group-1", Map.of("worker-1", 2L), 0L).get("worker-1").status()
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
            for (long delay : new long[]{0, -1, Long.MAX_VALUE, WorkerScoreEncoding.PAUSE_TIME_MILLIS}) {
                var results = scoreCore.deferObservedToRecovery("g", Map.of(
                        "hot", 20_000L, "recovery", -20_001L, "invalid", 0L), delay);
                assertEquals(3, results.size());
                results.values().forEach(result -> {
                    assertEquals(WorkerScoreTransitionStatus.INVALID, result.status());
                    assertEquals(null, result.score());
                });
                assertEquals(Map.of(), scoreCore.deferObservedToRecovery("g", Map.of(), delay));
            }
            assertEquals(WorkerScoreTransitionStatus.INVALID,
                    scoreCore.deferObservedToRecovery("g", Map.of("cold", -2L), 1_000)
                            .get("cold").status());
            assertEquals(Map.of(), scoreCore.deferObservedToRecovery("g", Map.of(), 1_000));
            var tooMany = new java.util.LinkedHashMap<String, Long>();
            for (int i = 0; i < 101; i++) tooMany.put("w" + i, -20_000L);
            assertThrows(IllegalArgumentException.class,
                    () -> scoreCore.deferObservedToRecovery("g", tooMany, 1_000));
            assertThrows(IllegalArgumentException.class,
                    () -> scoreCore.deferObservedToRecovery("g", null, 1_000));
        } finally {
            redisClient.shutdown();
        }
    }

    @Test
    void markInvalidationValidatesBoundsBeforeRedisAccess() {
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
                        () -> scoreCore.sealCurrentScoreHolds("group-1", ids));
                assertThrows(IllegalArgumentException.class,
                        () -> scoreCore.observeSchedulingStates("group-1", ids));
            }
            assertThrows(IllegalArgumentException.class,
                    () -> scoreCore.sealCurrentScoreHolds("group-1", null));
            assertThrows(IllegalArgumentException.class,
                    () -> scoreCore.sealCurrentScoreHolds(" ", List.of("w")));
        } finally {
            redisClient.shutdown();
        }
    }

    @Test
    void activeConfirmationValidatesInputsBeforeRedisAccess() {
        RedisClient client = RedisClient.create("redis://127.0.0.1:1");
        try (var scores = new RedisWorkerScoreCore(client, new RedisKeyspace("test_worker_score_unit"))) {
            assertEquals(Map.of(), scores.transferObservedHotScoreLeases("g", Map.of(), 1_000, true));
            assertEquals(WorkerScoreTransitionStatus.INVALID,
                    scores.transferObservedHotScoreLeases("g", Map.of("w", 200L), -1, true).get("w").status());
            assertThrows(IllegalArgumentException.class,
                    () -> scores.transferObservedHotScoreLeases("g", Map.of(" ", 200L), 1_000, true));
            assertThrows(IllegalArgumentException.class,
                    () -> scores.transferObservedHotScoreLeases("g", null, 1_000, true));

        } finally {
            client.shutdown();
        }
    }
}
