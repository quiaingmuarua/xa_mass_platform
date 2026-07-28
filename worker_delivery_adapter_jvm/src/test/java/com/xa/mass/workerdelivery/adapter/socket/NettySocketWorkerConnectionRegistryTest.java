package com.xa.mass.workerdelivery.adapter.socket;

import static com.xa.mass.workerdelivery.adapter.dispatch.WorkerCommandDelivery.CommandDeliveryAttempt.DELIVERED;
import static com.xa.mass.workerdelivery.adapter.dispatch.WorkerCommandDelivery.CommandDeliveryAttempt.REJECTED_BEFORE_SEND;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.TaskItemCommandMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class NettySocketWorkerConnectionRegistryTest {

    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    @Test
    void replacementPreservesTheLatestChannelAndWritesOneLine() {
        NettySocketWorkerConnectionRegistry registry = registry();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            registry.bind("worker-1", first);
            registry.bind("worker-1", second);
            registry.unbind("worker-1", first);

            assertThat(registry.deliver("worker-1", command()))
                    .isEqualTo(DELIVERED);
            assertThat(first.isOpen()).isFalse();
            assertThat(registry.activeConnectionCount()).isEqualTo(1);

            String delivered = second.readOutbound();
            assertThat(delivered).endsWith("\n");
            assertThat(codec.decodeWorkerConnectionMessage(
                    delivered.stripTrailing()
            )).isEqualTo(new TaskItemCommandMessage(command()));
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test
    void missingOrInactiveChannelRejectsBeforeSend() {
        NettySocketWorkerConnectionRegistry registry = registry();

        assertThat(registry.deliver("worker-1", command()))
                .isEqualTo(REJECTED_BEFORE_SEND);

        EmbeddedChannel inactive = new EmbeddedChannel();
        inactive.close();
        registry.bind("worker-1", inactive);

        assertThat(registry.deliver("worker-1", command()))
                .isEqualTo(REJECTED_BEFORE_SEND);
        assertThat(registry.activeConnectionCount()).isZero();
        inactive.finishAndReleaseAll();
    }

    private NettySocketWorkerConnectionRegistry registry() {
        return new NettySocketWorkerConnectionRegistry(
                codec,
                Duration.ofSeconds(1)
        );
    }

    private static WorkerCommandEnvelope command() {
        return new WorkerCommandEnvelope(
                "a5e9e10d-f78b-469e-93ab-864b49c189c1",
                WorkerMessageType.TASK_ITEM,
                2_000,
                "{}"
        );
    }
}
