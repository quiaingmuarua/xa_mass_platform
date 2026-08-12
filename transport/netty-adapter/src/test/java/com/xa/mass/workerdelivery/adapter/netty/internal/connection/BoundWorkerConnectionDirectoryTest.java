package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import static com.xa.mass.workerdelivery.adapter.netty.internal.gateway.DeliveryCommandTarget.DeliveryAttempt.RETRY_LATER;
import static com.xa.mass.workerdelivery.adapter.netty.internal.gateway.DeliveryCommandTarget.DeliveryAttempt.STARTED;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.netty.internal.socket.SocketLineFrameStrategy;
import com.xa.mass.workerdelivery.adapter.netty.internal.websocket.WebSocketTextFrameStrategy;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

class BoundWorkerConnectionDirectoryTest {

    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    @Test
    void webSocketReplacementAndOldDeactivationPreserveCurrentChannel() {
        BoundWorkerConnectionDirectory directory = directory(
                WebSocketTextFrameStrategy.INSTANCE
        );
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            directory.activate("worker-1", first);
            directory.activate("worker-1", second);
            directory.deactivate("worker-1", first);
            DeliveryCommand command = command();

            assertThat(directory.deliver("worker-1", command))
                    .isEqualTo(STARTED);
            assertThat(directory.activeConnectionCount()).isEqualTo(1);

            CloseWebSocketFrame replacement = first.readOutbound();
            TextWebSocketFrame delivered = second.readOutbound();
            try {
                assertThat(replacement.statusCode()).isEqualTo(1008);
                assertThat(codec.decodeDeliveryCommand(delivered.text()))
                        .isEqualTo(command);
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
    void socketReplacementWritesOneLineToTheLatestChannel() {
        BoundWorkerConnectionDirectory directory = directory(
                SocketLineFrameStrategy.INSTANCE
        );
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            directory.activate("worker-1", first);
            directory.activate("worker-1", second);
            directory.deactivate("worker-1", first);

            assertThat(directory.deliver("worker-1", command()))
                    .isEqualTo(STARTED);
            assertThat(first.isOpen()).isFalse();
            String delivered = second.readOutbound();
            assertThat(delivered).endsWith("\n");
            assertThat(codec.decodeDeliveryCommand(
                    delivered.stripTrailing()
            )).isEqualTo(command());
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test
    void missingOrInactiveChannelRetriesWithoutAWrite() {
        BoundWorkerConnectionDirectory directory = directory(
                SocketLineFrameStrategy.INSTANCE
        );
        assertThat(directory.deliver("worker-1", command()))
                .isEqualTo(RETRY_LATER);

        EmbeddedChannel inactive = new EmbeddedChannel();
        inactive.close();
        directory.activate("worker-1", inactive);

        assertThat(directory.deliver("worker-1", command()))
                .isEqualTo(RETRY_LATER);
        assertThat(directory.activeConnectionCount()).isZero();
        inactive.finishAndReleaseAll();
    }

    @Test
    void closingAnOldChannelCannotRemoveItsReplacement() {
        BoundWorkerConnectionDirectory directory = directory(
                WebSocketTextFrameStrategy.INSTANCE
        );
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            directory.activate("worker-1", first);
            directory.activate("worker-1", second);
            directory.close(
                    "worker-1",
                    first,
                    ConnectionCloseReason.TRANSPORT_ERROR
            );

            assertThat(directory.deliver("worker-1", command()))
                    .isEqualTo(STARTED);
            assertThat(directory.activeConnectionCount()).isEqualTo(1);
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    private BoundWorkerConnectionDirectory directory(
            TextFrameStrategy frameStrategy
    ) {
        return new BoundWorkerConnectionDirectory(codec, frameStrategy);
    }

    private static DeliveryCommand command() {
        return DeliveryCommand.create(
                TASK,
                WORKER,
                "test.observe",
                2_000,
                "{}",
                "context"
        );
    }
}
