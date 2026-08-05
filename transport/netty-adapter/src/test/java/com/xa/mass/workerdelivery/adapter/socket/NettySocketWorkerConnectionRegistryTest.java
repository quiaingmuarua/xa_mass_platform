package com.xa.mass.workerdelivery.adapter.socket;

import static com.xa.mass.workerdelivery.adapter.dispatch.WorkerCommandDelivery.CommandDeliveryAttempt.RETRY_LATER;
import static com.xa.mass.workerdelivery.adapter.dispatch.WorkerCommandDelivery.CommandDeliveryAttempt.STARTED;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

class NettySocketWorkerConnectionRegistryTest {

    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    @Test
    void replacementPreservesTheLatestChannelAndWritesOneLine() {
        NettySocketWorkerConnectionRegistry registry = registry();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            registry.activate("worker-1", first);
            registry.activate("worker-1", second);
            registry.deactivate("worker-1", first);

            assertThat(registry.deliver("worker-1", command()))
                    .isEqualTo(STARTED);
            assertThat(first.isOpen()).isFalse();
            assertThat(registry.activeConnectionCount()).isEqualTo(1);

            String delivered = second.readOutbound();
            assertThat(delivered).endsWith("\n");
            assertThat(codec.decodeWorkerCommand(
                    delivered.stripTrailing()
            ))
                    .isEqualTo(command());
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test
    void missingOrInactiveChannelRejectsBeforeSend() {
        NettySocketWorkerConnectionRegistry registry = registry();

        assertThat(registry.deliver("worker-1", command()))
                .isEqualTo(RETRY_LATER);

        EmbeddedChannel inactive = new EmbeddedChannel();
        inactive.close();
        registry.activate("worker-1", inactive);

        assertThat(registry.deliver("worker-1", command()))
                .isEqualTo(RETRY_LATER);
        assertThat(registry.activeConnectionCount()).isZero();
        inactive.finishAndReleaseAll();
    }

    private NettySocketWorkerConnectionRegistry registry() {
        return new NettySocketWorkerConnectionRegistry(codec);
    }

    private static WorkerCommand command() {
        return new WorkerCommand(
                "a5e9e10d-f78b-469e-93ab-864b49c189c1",
                TASK,
                WORKER,
                "test.observe",
                2_000,
                "{}",
                "context"
        );
    }
}
