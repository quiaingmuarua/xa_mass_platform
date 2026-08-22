package com.xa.mass.server.kernelbinding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.xa.mass.kernel.KernelOperationNotImplementedException;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.score.redis.RedisWorkerScoreCore;
import com.xa.mass.kernel.delivery.redis.RedisWorkerCommandRuntime;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import io.lettuce.core.RedisClient;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KernelOwnerAssemblyTest {

    @Test
    void schedulingWorkerScoreOperationsRemainExplicitGaps() {
        RedisClient redisClient = mock(RedisClient.class);
        RedisWorkerScoreCore scoreCore =
                new RedisWorkerScoreCore(
                        redisClient,
                        new RedisKeyspace("test_kernel_owner_unit")
                );

        assertThatThrownBy(() ->
                scoreCore.acquireHotAcquireCandidates("group-1", null, 1))
                .isInstanceOf(KernelOperationNotImplementedException.class)
                .satisfies(error -> {
                    var notImplemented =
                            (KernelOperationNotImplementedException) error;
                    assertThat(notImplemented.contractName())
                            .isEqualTo("WorkerScoreCore");
                    assertThat(notImplemented.operationName())
                            .isEqualTo(
                                    "acquire_hot_acquire_candidates"
                            );
                });
        org.mockito.Mockito.verifyNoInteractions(redisClient);
    }

    @Test
    void authoritativeWorkerCommandAppendRemainsAnExplicitJvmGap() {
        RedisClient redisClient = mock(RedisClient.class);
        RedisWorkerCommandRuntime commands = new RedisWorkerCommandRuntime(
                redisClient,
                new WorkerDeliveryCodec(),
                new RedisKeyspace("test_kernel_owner_unit")
        );

        assertThatThrownBy(() -> commands.appendWorkerCommands(
                "adapter-1",
                Map.of()
        ))
                .isInstanceOf(KernelOperationNotImplementedException.class)
                .satisfies(error -> {
                    var notImplemented =
                            (KernelOperationNotImplementedException) error;
                    assertThat(notImplemented.contractName())
                            .isEqualTo("WorkerCommandRuntime");
                    assertThat(notImplemented.operationName())
                            .isEqualTo("append_worker_commands");
                });
        org.mockito.Mockito.verifyNoInteractions(redisClient);
    }
}
