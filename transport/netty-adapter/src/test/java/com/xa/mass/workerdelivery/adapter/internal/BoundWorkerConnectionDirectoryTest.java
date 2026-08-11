package com.xa.mass.workerdelivery.adapter.internal;

import static com.xa.mass.workerdelivery.adapter.internal.DeliveryCommandTarget.DeliveryAttempt.RETRY_LATER;
import static com.xa.mass.workerdelivery.adapter.internal.DeliveryCommandTarget.DeliveryAttempt.STARTED;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;

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
        BoundWorkerConnectionDirectory directory = directory();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            directory.activate(
                    "worker-1",
                    first,
                    WebSocketTextFrameStrategy.INSTANCE
            );
            directory.activate(
                    "worker-1",
                    second,
                    WebSocketTextFrameStrategy.INSTANCE
            );
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
        BoundWorkerConnectionDirectory directory = directory();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            directory.activate(
                    "worker-1",
                    first,
                    SocketLineFrameStrategy.INSTANCE
            );
            directory.activate(
                    "worker-1",
                    second,
                    SocketLineFrameStrategy.INSTANCE
            );
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
        BoundWorkerConnectionDirectory directory = directory();
        assertThat(directory.deliver("worker-1", command()))
                .isEqualTo(RETRY_LATER);

        EmbeddedChannel inactive = new EmbeddedChannel();
        inactive.close();
        directory.activate(
                "worker-1",
                inactive,
                SocketLineFrameStrategy.INSTANCE
        );

        assertThat(directory.deliver("worker-1", command()))
                .isEqualTo(RETRY_LATER);
        assertThat(directory.activeConnectionCount()).isZero();
        inactive.finishAndReleaseAll();
    }

    @Test
    void closingAnOldChannelCannotRemoveItsReplacement() {
        BoundWorkerConnectionDirectory directory = directory();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            directory.activate(
                    "worker-1",
                    first,
                    WebSocketTextFrameStrategy.INSTANCE
            );
            directory.activate(
                    "worker-1",
                    second,
                    WebSocketTextFrameStrategy.INSTANCE
            );
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

    private BoundWorkerConnectionDirectory directory() {
        return new BoundWorkerConnectionDirectory(codec);
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
