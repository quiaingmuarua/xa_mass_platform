package com.xa.mass.server.assembly.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.delivery.redis.RedisWorkerCommandRuntime;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import io.lettuce.core.RedisClient;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KernelOwnerAssemblyTest {

    @Test
    void authoritativeWorkerCommandAppendShortCircuitsAnEmptyBatch() {
        RedisClient redisClient = mock(RedisClient.class);
        RedisWorkerCommandRuntime commands = new RedisWorkerCommandRuntime(
                redisClient,
                new WorkerDeliveryCodec(),
                new RedisKeyspace("test_kernel_owner_unit")
        );

        assertThat(commands.appendWorkerCommands("adapter-1", Map.of()))
                .isEmpty();
        org.mockito.Mockito.verifyNoInteractions(redisClient);
    }
}
