package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer.Response;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.AdapterConnectionCloseReason;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.NettyWorkerServer;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.TextWriteAttempt;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryCommandProcess.DeliveryAttempt;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkerConnectionMechanismTest {

    private final List<ScriptedHttpServer> httpServers = new ArrayList<>();

    @AfterEach
    void closeHttpServers() {
        httpServers.forEach(ScriptedHttpServer::close);
    }

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

            fixture.remoteApi.currentVerification().complete(null);
            awaitBound(fixture, channel);

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
            fixture.remoteApi.currentVerification().complete(null);
            awaitBound(fixture, channel);
            assertThat(fixture.reports).isEmpty();
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
        fixture.remoteApi.currentVerification().complete(null);
        awaitBound(fixture, first);
        first.finishAndReleaseAll();

        EmbeddedChannel reconnect = fixture.channel();
        try {
            reconnect.writeInbound(fixture.identity("worker-1"));

            awaitVerificationCalls(fixture.remoteApi, 1);
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

        awaitVerificationCalls(fixture.remoteApi, 1);
        assertThat(second.isActive()).isFalse();
        second.finishAndReleaseAll();
        first.finishAndReleaseAll();

        EmbeddedChannel retry = fixture.channel();
        try {
            retry.writeInbound(fixture.identity("worker-1"));
            awaitVerificationCalls(fixture.remoteApi, 2);
            fixture.remoteApi.currentVerification().complete(null);
            awaitBound(fixture, retry);
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

        fixture.remoteApi.currentVerification().complete(null);
        channel.runPendingTasks();

        assertThat(fixture.routes.activeChannel("worker-1")).isNull();
        EmbeddedChannel retry = fixture.channel();
        try {
            retry.writeInbound(fixture.identity("worker-1"));
            awaitVerificationCalls(fixture.remoteApi, 2);
        } finally {
            retry.finishAndReleaseAll();
        }
    }

    @Test
    void oldVerifiedChannelMaySubmitInFlightResultAfterReplacement() {
        Fixture fixture = new Fixture();
        EmbeddedChannel oldChannel = fixture.channel();
        oldChannel.writeInbound(fixture.identity("worker-1"));
        fixture.remoteApi.currentVerification().complete(null);
        awaitBound(fixture, oldChannel);

        EmbeddedChannel replacement = fixture.channel();
        String result = fixture.taskResult("worker-1");
        try {
            replacement.writeInbound(fixture.identity("worker-1"));
            oldChannel.writeInbound(result);

            assertThat(fixture.reports).containsExactly(result);
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
            fixture.remoteApi.currentVerification().complete(null);
            awaitBound(fixture, channel);

            DeliveryCommand command = DeliveryCommand.create(
                    TASK,
                    WORKER,
                    "test.observe",
                    2_000,
                    "{}",
                    "context"
            );
            assertThat(fixture.mechanism.deliver("worker-1", command))
                    .isEqualTo(DeliveryAttempt.STARTED);
            assertThat(fixture.network.writtenMessages)
                    .containsExactly(fixture.codec.encodeDeliveryCommand(
                            command
                    ));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void awaitBound(
            Fixture fixture,
            EmbeddedChannel channel
    ) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            channel.runPendingTasks();
            if (fixture.routes.inspectInbound(channel).kind()
                    == WorkerRouteRegistry.InboundKind.VERIFIED) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Worker route did not become verified");
    }

    private static void awaitVerificationCalls(
            PendingRouteHttpPeer remoteApi,
            int expected
    ) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (remoteApi.verificationCalls >= expected) {
                assertThat(remoteApi.verificationCalls).isEqualTo(expected);
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Route verification request did not start");
    }

    private final class Fixture {

        private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        private final PendingRouteHttpPeer remoteApi =
                new PendingRouteHttpPeer();
        private final List<String> reports = new ArrayList<>();
        private final DeliveryReportProcess.Acceptor reportAcceptor = batch -> {
            reports.addAll(batch);
            return DeliveryReportProcess.ReportIngressStatus.ACCEPTED;
        };
        private final WorkerRouteRegistry routes = new WorkerRouteRegistry();
        private final FakeNetworkServer network = new FakeNetworkServer();
        private final WorkerConnectionMechanism mechanism =
                new WorkerConnectionMechanism(
                        routes,
                        network,
                        remoteApi.server.client(),
                        codec,
                        reportAcceptor,
                        "adapter-1",
                        Duration.ofSeconds(1)
                );

        private EmbeddedChannel channel() {
            return new EmbeddedChannel(mechanism);
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

    private final class PendingRouteHttpPeer {

        private volatile CompletableFuture<Void> verification;
        private volatile int verificationCalls;
        private final ScriptedHttpServer server;

        private PendingRouteHttpPeer() {
            server = new ScriptedHttpServer(this::handle);
            httpServers.add(server);
        }

        private Response handle(ScriptedHttpServer.Request request) {
            CompletableFuture<Void> current = new CompletableFuture<>();
            verification = current;
            verificationCalls++;
            try {
                current.join();
                return new Response(204, "");
            } catch (CompletionException error) {
                return new Response(503, "{}");
            }
        }

        private CompletableFuture<Void> currentVerification() {
            long deadline = System.nanoTime()
                    + Duration.ofSeconds(2).toNanos();
            while (System.nanoTime() < deadline) {
                CompletableFuture<Void> current = verification;
                if (current != null && !current.isDone()) {
                    return current;
                }
                Thread.onSpinWait();
            }
            throw new AssertionError("Route verification request did not start");
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
