package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import static com.xa.mass.workerdelivery.adapter.netty.internal.gateway.DeliveryCommandTarget.DeliveryAttempt.RETRY_LATER;
import static com.xa.mass.workerdelivery.adapter.netty.internal.gateway.DeliveryCommandTarget.DeliveryAttempt.STARTED;
import static com.xa.mass.workerdelivery.adapter.netty.internal.gateway.DeliveryCommandTarget.DeliveryAttempt.UNKNOWN;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xa.mass.workerdelivery.adapter.netty.internal.network.AdapterNetworkProtocol;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WorkerRouteDirectoryTest {

    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    @Test
    void replacementAndOldDeactivationPreserveCurrentChannel() {
        WorkerRouteDirectory directory = directory();
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
            assertThat(first.isOpen()).isFalse();
            assertThat(codec.decodeDeliveryCommand(second.readOutbound()))
                    .isEqualTo(command());
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test
    void sameWorkerIdIsIsolatedAcrossAdapterDirectories() {
        WorkerRouteDirectory firstDirectory = directory();
        WorkerRouteDirectory secondDirectory = directory();
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
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test
    void missingInactiveAndNonWritableChannelsRetry() {
        WorkerRouteDirectory directory = directory();
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
        WorkerRouteDirectory directory = directory();
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
        WorkerRouteDirectory directory = directory();
        DeferredWrite firstWrites = new DeferredWrite();
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
    void verificationIsFirstWinsAndClearRemovesAllRouteState() {
        WorkerRouteDirectory directory = directory();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            assertThat(directory.beginVerification("worker-1", first))
                    .isTrue();
            assertThat(directory.beginVerification("worker-1", second))
                    .isFalse();
            directory.cancelVerification("worker-1", first);
            verifyAndActivate(directory, "worker-1", second);
            directory.deactivate("worker-1", second);

            assertThat(directory.isRouteVerified("worker-1")).isTrue();
            assertThat(directory.pendingVerificationCount()).isZero();
            directory.clear();
            assertThat(directory.isRouteVerified("worker-1")).isFalse();
            assertThat(directory.verifiedWorkerCount()).isZero();
            assertThat(directory.activeConnectionCount()).isZero();
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    private WorkerRouteDirectory directory() {
        return new WorkerRouteDirectory(
                codec,
                AdapterNetworkProtocol.socket(Duration.ofSeconds(1))
        );
    }

    private static void verifyAndActivate(
            WorkerRouteDirectory directory,
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

    private static final class DeferredWrite
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
