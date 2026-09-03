package com.xa.mass.kernel.score.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.kernel.KernelOperationNotImplementedException;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import io.lettuce.core.RedisClient;
import java.util.ArrayList;
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
                    scoreCore.exhaustRecoveryRecheck(
                            "group-1", "worker-1", -200L, 0
                    ).status()
            );
            assertEquals(
                    WorkerScoreTransitionStatus.INVALID,
                    scoreCore.holdObservedHotForServiceabilityProbes(
                            "group-1",
                            Map.of("worker-1", 0L)
                    ).get("worker-1").status()
            );
            assertEquals(
                    WorkerScoreTransitionStatus.INVALID,
                    scoreCore.advanceObservedRecoveryRechecks(
                            "group-1",
                            Map.of("worker-1", 200L)
                    ).get("worker-1").status()
            );
            assertEquals(
                    WorkerScoreTransitionStatus.INVALID,
                    scoreCore.applyServiceabilityEvidence(
                            "group-1",
                            Map.of("worker-1", 0L),
                            WorkerScorePolarity.HOT_ACQUIRE
                    ).get("worker-1").status()
            );
        } finally {
            redisClient.shutdown();
        }
    }

    @Test
    void unrelatedProductionGapsRemainExplicit() {
        RedisClient redisClient = RedisClient.create(
                "redis://127.0.0.1:1"
        );
        try {
            RedisWorkerScoreCore scoreCore = new RedisWorkerScoreCore(
                    redisClient,
                    new RedisKeyspace("test_worker_score_unit")
            );
            assertOperation(
                    "mark_current_lease_dirty",
                    () -> scoreCore.markCurrentLeaseDirty(
                            "group-1",
                            "worker-1"
                    )
            );
        } finally {
            redisClient.shutdown();
        }
    }

    @Test
    void activeLeaseObservationValidatesBoundsBeforeRedisAccess() {
        RedisClient redisClient = RedisClient.create(
                "redis://127.0.0.1:1"
        );
        try {
            RedisWorkerScoreCore scoreCore = new RedisWorkerScoreCore(
                    redisClient,
                    new RedisKeyspace("test_worker_score_unit")
            );

            assertEquals(Map.of(), scoreCore.observeActiveHotScoreLeases(
                    "group-1",
                    List.of(),
                    1_000L
            ));
            assertEquals(Map.of(), scoreCore.observeActiveHotScoreLeases(
                    "group-1",
                    List.of("worker-1"),
                    -1L
            ));
            assertThrows(IllegalArgumentException.class, () ->
                    scoreCore.observeActiveHotScoreLeases(
                            "group-1",
                            List.of("worker-1", "worker-1"),
                            1_000L
                    )
            );
            List<String> tooMany = new ArrayList<>();
            for (int index = 0; index < 101; index++) {
                tooMany.add("worker-" + index);
            }
            assertThrows(IllegalArgumentException.class, () ->
                    scoreCore.observeActiveHotScoreLeases(
                            "group-1",
                            tooMany,
                            1_000L
                    )
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
        assertEquals("WorkerScoreCore", error.contractName());
        assertEquals(operation, error.operationName());
    }
}
