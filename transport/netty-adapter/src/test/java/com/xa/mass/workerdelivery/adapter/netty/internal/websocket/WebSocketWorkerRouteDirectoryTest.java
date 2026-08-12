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

class WebSocketWorkerRouteDirectoryTest {

    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    @Test
    void replacementAndOldDeactivationPreserveCurrentChannel() {
        WebSocketWorkerRouteDirectory directory = directory();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            verifyAndActivate(directory, "worker-1", first);
            assertThat(directory.activateIfVerified("worker-1", second))
                    .isTrue();
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
        WebSocketWorkerRouteDirectory firstDirectory = directory();
        WebSocketWorkerRouteDirectory secondDirectory = directory();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            verifyAndActivate(firstDirectory, "worker-1", first);
            verifyAndActivate(secondDirectory, "worker-1", second);
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
        WebSocketWorkerRouteDirectory directory = directory();
        assertThat(directory.deliver("worker-1", command()))
                .isEqualTo(RETRY_LATER);

        EmbeddedChannel inactive = new EmbeddedChannel();
        inactive.close();
        verifyAndActivate(directory, "worker-1", inactive);
        assertThat(directory.deliver("worker-1", command()))
                .isEqualTo(RETRY_LATER);
        assertThat(directory.activeConnectionCount()).isZero();
        inactive.finishAndReleaseAll();

        Channel nonWritable = mock(Channel.class);
        when(nonWritable.isActive()).thenReturn(true);
        when(nonWritable.isWritable()).thenReturn(false);
        assertThat(directory.activateIfVerified("worker-1", nonWritable))
                .isTrue();
        assertThat(directory.deliver("worker-1", command()))
                .isEqualTo(RETRY_LATER);
        assertThat(directory.activeConnectionCount()).isEqualTo(1);
    }

    @Test
    void synchronousWriteFailureIsUnknownAndRemovesTheExactChannel() {
        WebSocketWorkerRouteDirectory directory = directory();
        Channel channel = mock(Channel.class);
        when(channel.isActive()).thenReturn(true);
        when(channel.isWritable()).thenReturn(true);
        when(channel.isOpen()).thenReturn(true);
        when(channel.writeAndFlush(any())).thenThrow(
                new IllegalStateException("write failed")
        );
        verifyAndActivate(directory, "worker-1", channel);

        assertThat(directory.deliver("worker-1", command()))
                .isEqualTo(UNKNOWN);
        assertThat(directory.activeConnectionCount()).isZero();
    }

    @Test
    void staleAsynchronousFailureCannotRemoveReplacement() {
        WebSocketWorkerRouteDirectory directory = directory();
        DeferredTextWrite firstWrites = new DeferredTextWrite();
        EmbeddedChannel first = new EmbeddedChannel(firstWrites);
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            verifyAndActivate(directory, "worker-1", first);
            assertThat(directory.deliver("worker-1", command()))
                    .isEqualTo(STARTED);

            assertThat(directory.activateIfVerified("worker-1", second))
                    .isTrue();
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

    @Test
    void pendingVerificationIsFirstWinsAndCancellationAllowsRetry() {
        WebSocketWorkerRouteDirectory directory = directory();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            assertThat(directory.beginVerification("worker-1", first))
                    .isTrue();
            assertThat(directory.beginVerification("worker-1", second))
                    .isFalse();
            assertThat(directory.pendingVerificationCount()).isEqualTo(1);

            directory.cancelVerification("worker-1", first);

            assertThat(directory.beginVerification("worker-1", second))
                    .isTrue();
            assertThat(directory.completeVerificationAndActivate(
                    "worker-1",
                    second
            )).isTrue();
            assertThat(directory.verifiedWorkerCount()).isEqualTo(1);
            assertThat(directory.pendingVerificationCount()).isZero();
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test
    void deactivationRetainsVerificationAndClearRemovesAllRouteState() {
        WebSocketWorkerRouteDirectory directory = directory();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            verifyAndActivate(directory, "worker-1", first);
            directory.deactivate("worker-1", first);

            assertThat(directory.isRouteVerified("worker-1")).isTrue();
            assertThat(directory.activeConnectionCount()).isZero();
            assertThat(directory.activateIfVerified("worker-1", second))
                    .isTrue();

            directory.clear();

            assertThat(directory.isRouteVerified("worker-1")).isFalse();
            assertThat(directory.verifiedWorkerCount()).isZero();
            assertThat(directory.pendingVerificationCount()).isZero();
            assertThat(directory.activeConnectionCount()).isZero();
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    private WebSocketWorkerRouteDirectory directory() {
        return new WebSocketWorkerRouteDirectory(codec);
    }

    private static void verifyAndActivate(
            WebSocketWorkerRouteDirectory directory,
            String workerId,
            Channel channel
    ) {
        assertThat(directory.beginVerification(workerId, channel)).isTrue();
        assertThat(directory.completeVerificationAndActivate(
                workerId,
                channel
        )).isTrue();
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
