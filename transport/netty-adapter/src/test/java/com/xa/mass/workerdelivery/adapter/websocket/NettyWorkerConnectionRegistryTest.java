package com.xa.mass.workerdelivery.adapter.websocket;

import static com.xa.mass.workerdelivery.adapter.dispatch.WorkerCommandDelivery.CommandDeliveryAttempt.RETRY_LATER;
import static com.xa.mass.workerdelivery.adapter.dispatch.WorkerCommandDelivery.CommandDeliveryAttempt.STARTED;
import static com.xa.mass.workerdelivery.adapter.websocket.WorkerConnectionRegistry.ConnectionCloseReason.TRANSPORT_ERROR;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessageType.TASK_ITEM_COMMAND;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.ReferenceCountUtil;
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
                    .isEqualTo(STARTED);
            assertThat(registry.activeConnectionCount()).isEqualTo(1);

            CloseWebSocketFrame replacement = first.readOutbound();
            TextWebSocketFrame delivered = second.readOutbound();
            try {
                assertThat(replacement.statusCode()).isEqualTo(1008);
                var message = codec.decodeWorkerConnectionMessage(
                        delivered.text()
                );
                assertThat(message.messageType())
                        .isEqualTo(TASK_ITEM_COMMAND.name());
                assertThat(codec.decodeWorkerCommand(message.payload()))
                        .isEqualTo(command());
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
                .isEqualTo(RETRY_LATER);

        EmbeddedChannel inactive = new EmbeddedChannel();
        inactive.close();
        registry.bind("worker-1", inactive);

        assertThat(registry.deliver("worker-1", command()))
                .isEqualTo(RETRY_LATER);
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
                    .isEqualTo(STARTED);
            assertThat(registry.activeConnectionCount()).isEqualTo(1);
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    private NettyWorkerConnectionRegistry registry() {
        return new NettyWorkerConnectionRegistry(codec);
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
