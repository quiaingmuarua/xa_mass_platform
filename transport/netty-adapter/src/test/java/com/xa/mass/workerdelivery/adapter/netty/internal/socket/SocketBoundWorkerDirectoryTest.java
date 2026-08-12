package com.xa.mass.workerdelivery.adapter.netty.internal.socket;

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
import org.junit.jupiter.api.Test;

class SocketBoundWorkerDirectoryTest {

    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    @Test
    void replacementAndOldDeactivationPreserveCurrentChannel() {
        SocketBoundWorkerDirectory directory = directory();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            directory.activate("worker-1", first);
            directory.activate("worker-1", second);
            directory.deactivate("worker-1", first);

            assertThat(directory.deliver("worker-1", command()))
                    .isEqualTo(STARTED);
            assertThat(directory.activeConnectionCount()).isEqualTo(1);
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
    void sameWorkerIdIsIsolatedAcrossAdapterDirectories() {
        SocketBoundWorkerDirectory firstDirectory = directory();
        SocketBoundWorkerDirectory secondDirectory = directory();
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
        SocketBoundWorkerDirectory directory = directory();
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
        SocketBoundWorkerDirectory directory = directory();
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
        SocketBoundWorkerDirectory directory = directory();
        DeferredLineWrite firstWrites = new DeferredLineWrite();
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

    private SocketBoundWorkerDirectory directory() {
        return new SocketBoundWorkerDirectory(codec);
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

    private static final class DeferredLineWrite
            extends ChannelOutboundHandlerAdapter {

        private ChannelPromise commandPromise;

        @Override
        public void write(
                ChannelHandlerContext context,
                Object message,
                ChannelPromise promise
        ) {
            if (message instanceof String) {
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
