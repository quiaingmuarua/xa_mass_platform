package com.xa.mass.workerdelivery.adapter.netty.internal.websocket;

import static com.xa.mass.workerdelivery.adapter.netty.internal.gateway.DeliveryCommandTarget.DeliveryAttempt.RETRY_LATER;
import static com.xa.mass.workerdelivery.adapter.netty.internal.gateway.DeliveryCommandTarget.DeliveryAttempt.STARTED;
import static com.xa.mass.workerdelivery.adapter.netty.internal.gateway.DeliveryCommandTarget.DeliveryAttempt.UNKNOWN;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

class WebSocketBoundWorkerDirectoryTest {

    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    @Test
    void replacementAndOldDeactivationPreserveCurrentChannel() {
        WebSocketBoundWorkerDirectory directory = directory();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            directory.activate("worker-1", first);
            directory.activate("worker-1", second);
            directory.deactivate("worker-1", first);

            assertThat(directory.deliver("worker-1", command()))
                    .isEqualTo(STARTED);
            assertThat(directory.activeConnectionCount()).isEqualTo(1);

            CloseWebSocketFrame replacement = first.readOutbound();
            TextWebSocketFrame delivered = second.readOutbound();
            try {
                assertThat(replacement.statusCode()).isEqualTo(1008);
                assertThat(codec.decodeDeliveryCommand(delivered.text()))
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
    void sameWorkerIdIsIsolatedAcrossAdapterDirectories() {
        WebSocketBoundWorkerDirectory firstDirectory = directory();
        WebSocketBoundWorkerDirectory secondDirectory = directory();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            firstDirectory.activate("worker-1", first);
            secondDirectory.activate("worker-1", second);
            firstDirectory.deactivate("worker-1", first);

            assertThat(firstDirectory.deliver("worker-1", command()))
                    .isEqualTo(RETRY_LATER);
            assertThat(secondDirectory.deliver("worker-1", command()))
                    .isEqualTo(STARTED);
            assertThat(secondDirectory.activeConnectionCount()).isEqualTo(1);
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test
    void missingInactiveAndNonWritableChannelsRetry() {
        WebSocketBoundWorkerDirectory directory = directory();
        assertThat(directory.deliver("worker-1", command()))
                .isEqualTo(RETRY_LATER);

        EmbeddedChannel inactive = new EmbeddedChannel();
        inactive.close();
        directory.activate("worker-1", inactive);
        assertThat(directory.deliver("worker-1", command()))
                .isEqualTo(RETRY_LATER);
        assertThat(directory.activeConnectionCount()).isZero();
        inactive.finishAndReleaseAll();

        Channel nonWritable = mock(Channel.class);
        when(nonWritable.isActive()).thenReturn(true);
        when(nonWritable.isWritable()).thenReturn(false);
        directory.activate("worker-1", nonWritable);
        assertThat(directory.deliver("worker-1", command()))
                .isEqualTo(RETRY_LATER);
        assertThat(directory.activeConnectionCount()).isEqualTo(1);
    }

    @Test
    void synchronousWriteFailureIsUnknownAndRemovesTheExactChannel() {
        WebSocketBoundWorkerDirectory directory = directory();
        Channel channel = mock(Channel.class);
        when(channel.isActive()).thenReturn(true);
        when(channel.isWritable()).thenReturn(true);
        when(channel.isOpen()).thenReturn(true);
        when(channel.writeAndFlush(any())).thenThrow(
                new IllegalStateException("write failed")
        );
        directory.activate("worker-1", channel);

        assertThat(directory.deliver("worker-1", command()))
                .isEqualTo(UNKNOWN);
        assertThat(directory.activeConnectionCount()).isZero();
    }

    @Test
    void staleAsynchronousFailureCannotRemoveReplacement() {
        WebSocketBoundWorkerDirectory directory = directory();
        DeferredTextWrite firstWrites = new DeferredTextWrite();
        EmbeddedChannel first = new EmbeddedChannel(firstWrites);
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            directory.activate("worker-1", first);
            assertThat(directory.deliver("worker-1", command()))
                    .isEqualTo(STARTED);

            directory.activate("worker-1", second);
            firstWrites.failCommand();
            first.runPendingTasks();

            assertThat(first.isOpen()).isFalse();
            assertThat(directory.deliver("worker-1", command()))
                    .isEqualTo(STARTED);
            assertThat(directory.activeConnectionCount()).isEqualTo(1);
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    private WebSocketBoundWorkerDirectory directory() {
        return new WebSocketBoundWorkerDirectory(codec);
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

    private static final class DeferredTextWrite
            extends ChannelOutboundHandlerAdapter {

        private ChannelPromise commandPromise;

        @Override
        public void write(
                ChannelHandlerContext context,
                Object message,
                ChannelPromise promise
        ) {
            if (message instanceof TextWebSocketFrame) {
                ReferenceCountUtil.release(message);
                commandPromise = promise;
                return;
            }
            context.write(message, promise);
        }

        void failCommand() {
            commandPromise.tryFailure(new IllegalStateException("late"));
        }
    }
}
