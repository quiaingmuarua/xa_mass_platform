package com.xa.mass.workerdelivery.adapter.websocket;

import static com.xa.mass.workerdelivery.adapter.dispatch.WorkerCommandDelivery.CommandDeliveryAttempt.DELIVERED;
import static com.xa.mass.workerdelivery.adapter.dispatch.WorkerCommandDelivery.CommandDeliveryAttempt.REJECTED_BEFORE_SEND;
import static com.xa.mass.workerdelivery.adapter.websocket.WorkerConnectionRegistry.ConnectionCloseReason.TRANSPORT_ERROR;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.TaskItemCommandMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class NettyWorkerConnectionRegistryTest {

    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    @Test
    void replacementAndOldUnbindPreserveCurrentChannel() {
        NettyWorkerConnectionRegistry registry = registry();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            registry.bind("worker-1", first);
            registry.bind("worker-1", second);
            registry.unbind("worker-1", first);

            assertThat(registry.deliver("worker-1", command()))
                    .isEqualTo(DELIVERED);
            assertThat(registry.activeConnectionCount()).isEqualTo(1);

            CloseWebSocketFrame replacement = first.readOutbound();
            TextWebSocketFrame delivered = second.readOutbound();
            try {
                assertThat(replacement.statusCode()).isEqualTo(1008);
            assertThat(codec.decodeWorkerConnectionMessage(
                    delivered.text()
            )).isEqualTo(new TaskItemCommandMessage(command()));
            } finally {
                ReferenceCountUtil.release(replacement);
                ReferenceCountUtil.release(delivered);
            }
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test
    void missingOrInactiveChannelRejectsBeforeSend() {
        NettyWorkerConnectionRegistry registry = registry();

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

    @Test
    void closingAnOldChannelCannotRemoveItsReplacement() {
        NettyWorkerConnectionRegistry registry = registry();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            registry.bind("worker-1", first);
            registry.bind("worker-1", second);
            registry.close(
                    "worker-1",
                    first,
                    TRANSPORT_ERROR
            );

            assertThat(registry.deliver("worker-1", command()))
                    .isEqualTo(DELIVERED);
            assertThat(registry.activeConnectionCount()).isEqualTo(1);
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    private NettyWorkerConnectionRegistry registry() {
        return new NettyWorkerConnectionRegistry(
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
