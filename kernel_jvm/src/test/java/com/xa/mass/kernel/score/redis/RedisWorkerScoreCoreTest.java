package com.xa.mass.kernel.score.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.kernel.KernelOperationNotImplementedException;
import io.lettuce.core.RedisClient;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedisWorkerScoreCoreTest {

    @Test
    void recoveryTransitionGapsRemainExplicit() {
        RedisClient redisClient = RedisClient.create(
                "redis://127.0.0.1:1"
        );
        try {
            RedisWorkerScoreCore scoreCore = new RedisWorkerScoreCore(
                    redisClient,
                    "test"
            );

            assertOperation(
                    "demote_observed_worker_leases_to_recovery",
                    () -> scoreCore.demoteObservedWorkerLeasesToRecovery(
                            "group-1",
                            Map.of("worker-1", 1L)
                    )
            );
            assertOperation(
                    "toggle_current_polarity",
                    () -> scoreCore.toggleCurrentPolarity(
                            "group-1",
                            "worker-1",
                            1L
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
