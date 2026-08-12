package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.netty.internal.gateway.BoundedDeliveryReportQueue;
import com.xa.mass.workerdelivery.adapter.netty.internal.gateway.DeliveryReportPump;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.AdapterConnectionCloseReason;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.NettyWorkerServer;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.TextWriteAttempt;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class WorkerConnectionMechanismTest {

    @Test
    void successfulVerificationMovesDerivedPhaseToBound() {
        Fixture fixture = new Fixture();
        EmbeddedChannel channel = fixture.channel();
        try {
            channel.writeInbound(fixture.identity("worker-1"));
            assertThat(fixture.routes.inspectInbound(channel).kind())
                    .isEqualTo(
                            WorkerRouteRegistry.InboundKind
                                    .VERIFICATION_PENDING
                    );

            fixture.gateway.currentVerification().complete(null);
            channel.runPendingTasks();

            assertThat(fixture.routes.inspectInbound(channel).kind())
                    .isEqualTo(WorkerRouteRegistry.InboundKind.VERIFIED);
            assertThat(fixture.routes.activeChannel("worker-1"))
                    .isSameAs(channel);
        } finally {
            channel.finishAndReleaseAll();
        }
        assertThat(fixture.routes.activeChannel("worker-1")).isNull();
    }

    @Test
    void messagesDuringVerificationAreDroppedInsteadOfBuffered() {
        Fixture fixture = new Fixture();
        EmbeddedChannel channel = fixture.channel();
        try {
            channel.writeInbound(fixture.identity("worker-1"));
            channel.writeInbound(fixture.taskResult("worker-1"));
            fixture.gateway.currentVerification().complete(null);
            channel.runPendingTasks();
            fixture.reportPump().run();

            assertThat(fixture.gateway.appendedResults).isEmpty();
            assertThat(fixture.routes.activeChannel("worker-1"))
                    .isSameAs(channel);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void verifiedReconnectSkipsGatewayAndReplacesPhysicalChannel() {
        Fixture fixture = new Fixture();
        EmbeddedChannel first = fixture.channel();
        first.writeInbound(fixture.identity("worker-1"));
        fixture.gateway.currentVerification().complete(null);
        first.runPendingTasks();
        first.finishAndReleaseAll();

        EmbeddedChannel reconnect = fixture.channel();
        try {
            reconnect.writeInbound(fixture.identity("worker-1"));

            assertThat(fixture.gateway.verificationCalls).isEqualTo(1);
            assertThat(fixture.routes.activeChannel("worker-1"))
                    .isSameAs(reconnect);
        } finally {
            reconnect.finishAndReleaseAll();
        }
    }

    @Test
    void firstPendingConnectionWinsAndDisconnectAllowsRetry() {
        Fixture fixture = new Fixture();
        EmbeddedChannel first = fixture.channel();
        EmbeddedChannel second = fixture.channel();
        first.writeInbound(fixture.identity("worker-1"));
        second.writeInbound(fixture.identity("worker-1"));

        assertThat(fixture.gateway.verificationCalls).isEqualTo(1);
        assertThat(second.isActive()).isFalse();
        second.finishAndReleaseAll();
        first.finishAndReleaseAll();

        EmbeddedChannel retry = fixture.channel();
        try {
            retry.writeInbound(fixture.identity("worker-1"));
            assertThat(fixture.gateway.verificationCalls).isEqualTo(2);
            fixture.gateway.currentVerification().complete(null);
            retry.runPendingTasks();
            assertThat(fixture.routes.activeChannel("worker-1"))
                    .isSameAs(retry);
        } finally {
            retry.finishAndReleaseAll();
        }
    }

    @Test
    void lateVerificationSuccessCannotReactivateClosedChannel() {
        Fixture fixture = new Fixture();
        EmbeddedChannel channel = fixture.channel();
        channel.writeInbound(fixture.identity("worker-1"));
        channel.finishAndReleaseAll();

        fixture.gateway.currentVerification().complete(null);
        channel.runPendingTasks();

        assertThat(fixture.routes.activeChannel("worker-1")).isNull();
        EmbeddedChannel retry = fixture.channel();
        try {
            retry.writeInbound(fixture.identity("worker-1"));
            assertThat(fixture.gateway.verificationCalls).isEqualTo(2);
        } finally {
            retry.finishAndReleaseAll();
        }
    }

    @Test
    void oldVerifiedChannelMaySubmitInFlightResultAfterReplacement() {
        Fixture fixture = new Fixture();
        EmbeddedChannel oldChannel = fixture.channel();
        oldChannel.writeInbound(fixture.identity("worker-1"));
        fixture.gateway.currentVerification().complete(null);
        oldChannel.runPendingTasks();

        EmbeddedChannel replacement = fixture.channel();
        String result = fixture.taskResult("worker-1");
        try {
            replacement.writeInbound(fixture.identity("worker-1"));
            oldChannel.writeInbound(result);
            fixture.reportPump().run();

            assertThat(fixture.gateway.appendedResults)
                    .containsExactly(List.of(result));
            assertThat(fixture.routes.activeChannel("worker-1"))
                    .isSameAs(replacement);
        } finally {
            oldChannel.finishAndReleaseAll();
            replacement.finishAndReleaseAll();
        }
    }

    @Test
    void commandDeliveryUsesOnlyThePhysicalServerPort() {
        Fixture fixture = new Fixture();
        EmbeddedChannel channel = fixture.channel();
        try {
            channel.writeInbound(fixture.identity("worker-1"));
            fixture.gateway.currentVerification().complete(null);
            channel.runPendingTasks();

            DeliveryCommand command = DeliveryCommand.create(
                    TASK,
                    WORKER,
                    "test.observe",
                    2_000,
                    "{}",
                    "context"
            );
            assertThat(fixture.mechanism.deliver("worker-1", command))
                    .isEqualTo(
                            com.xa.mass.workerdelivery.adapter.netty.internal
                                    .gateway.DeliveryCommandTarget
                                    .DeliveryAttempt.STARTED
                    );
            assertThat(fixture.network.writtenMessages)
                    .containsExactly(fixture.codec.encodeDeliveryCommand(
                            command
                    ));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static final class Fixture {

        private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        private final PendingVerificationGateway gateway =
                new PendingVerificationGateway();
        private final BoundedDeliveryReportQueue reportQueue =
                new BoundedDeliveryReportQueue(4);
        private final WorkerRouteRegistry routes = new WorkerRouteRegistry();
        private final FakeNetworkServer network = new FakeNetworkServer();
        private final WorkerConnectionMechanism mechanism =
                new WorkerConnectionMechanism(
                        routes,
                        network,
                        gateway,
                        codec,
                        reportQueue,
                        "adapter-1",
                        Duration.ofSeconds(1)
                );

        private EmbeddedChannel channel() {
            return new EmbeddedChannel(mechanism);
        }

        private DeliveryReportPump reportPump() {
            return new DeliveryReportPump(
                    gateway,
                    "adapter-1",
                    reportQueue
            );
        }

        private String taskResult(String workerId) {
            return codec.encodeDeliveryReport(DeliveryReport.create(
                    WORKER,
                    workerId,
                    TASK,
                    "test.observe",
                    "200",
                    "null",
                    "context"
            ));
        }

        private String identity(String workerId) {
            return codec.encodeDeliveryReport(DeliveryReport.create(
                    WORKER,
                    workerId,
                    ADAPTER,
                    WORKER_CONNECTION_IDENTIFY_EVENT_CODE,
                    "200",
                    "null",
                    ""
            ));
        }
    }

    private static final class PendingVerificationGateway
            implements WorkerDeliveryGatewayClient {

        private final List<List<String>> appendedResults = new ArrayList<>();
        private CompletableFuture<Void> verification =
                new CompletableFuture<>();
        private int verificationCalls;

        @Override
        public Map<String, DeliveryCommand> consumeWorkerCommands(
                String endpointManagerId,
                int limit
        ) {
            return Map.of();
        }

        @Override
        public void appendResults(
                String endpointManagerId,
                List<String> encodedDeliveryReports
        ) {
            appendedResults.add(List.copyOf(encodedDeliveryReports));
        }

        @Override
        public CompletionStage<Void> verifyWorkerRoute(
                String endpointManagerId,
                String workerId
        ) {
            verificationCalls++;
            if (verification.isDone()) {
                verification = new CompletableFuture<>();
            }
            return verification;
        }

        private CompletableFuture<Void> currentVerification() {
            return verification;
        }
    }

    private static final class FakeNetworkServer
            implements NettyWorkerServer {

        private final List<String> writtenMessages = new ArrayList<>();

        @Override
        public void start(ChannelHandler sharedConnectionHandler) {
        }

        @Override
        public TextWriteAttempt writeText(Channel channel, String message) {
            if (!channel.isActive() || !channel.isWritable()) {
                return TextWriteAttempt.RETRY_LATER;
            }
            writtenMessages.add(message);
            return TextWriteAttempt.STARTED;
        }

        @Override
        public void writeTextAndClose(
                Channel channel,
                String message,
                AdapterConnectionCloseReason reason
        ) {
            writtenMessages.add(message);
            channel.close();
        }

        @Override
        public void closeConnection(
                Channel channel,
                AdapterConnectionCloseReason reason
        ) {
            if (reason != AdapterConnectionCloseReason.REPLACED) {
                channel.close();
            }
        }

        @Override
        public void close() {
        }
    }
}
