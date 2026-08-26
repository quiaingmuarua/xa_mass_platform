package com.xa.mass.kernel.serviceability.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import io.lettuce.core.RedisClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedisWorkerServiceabilityRuntimeTest {

    @Test
    void resultConsumeAndProbeOfferValidateBoundsBeforeRedisAccess() {
        RedisClient redisClient = RedisClient.create("redis://127.0.0.1:1");
        try (RedisWorkerServiceabilityRuntime runtime =
                     new RedisWorkerServiceabilityRuntime(
                             redisClient,
                             new WorkerDeliveryCodec(),
                             new RedisKeyspace("test_serviceability_unit")
                     )) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> runtime.consumeAdapterEvidenceResults(0)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> runtime.consumeAdapterEvidenceResults(101)
            );
            assertEquals(
                    Map.of(),
                    runtime.offerProbeRequests("adapter-1", List.of())
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> runtime.offerProbeRequests(
                            "adapter-1",
                            List.of("worker-1", "worker-1")
                    )
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> runtime.offerProbeRequests(
                            "adapter-1",
                            java.util.Collections.nCopies(101, "worker")
                    )
            );
        } finally {
            redisClient.shutdown();
        }
    }
}
