package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.SYSTEM;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer.Response;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.AdapterConnectionCloseReason;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.NettyWorkerServer;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.TextWriteAttempt;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism.DeliveryAttempt;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism.CloseCurrentOutcome;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.DeliveryReportRemoteApi;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.WorkerDeliveryHttpClient;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.WorkerRouteRemoteApi;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
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
    void reportsOnlyEffectiveAvailabilityTransitions() {
        Fixture fixture = new Fixture();
        EmbeddedChannel first = fixture.channel();
        EmbeddedChannel reconnect = null;
        try {
            first.writeInbound(fixture.identity("worker-1"));
            fixture.remoteApi.currentVerification().complete(null);
            awaitBound(fixture, first);
            fixture.reportProcess.round();
            assertAvailability(fixture, true);

            fixture.controlReports.clear();
            first.finishAndReleaseAll();
            fixture.reportProcess.round();
            assertAvailability(fixture, false);

            fixture.controlReports.clear();
            reconnect = fixture.channel();
            reconnect.writeInbound(fixture.identity("worker-1"));
            awaitBound(fixture, reconnect);
            fixture.reportProcess.round();
            assertAvailability(fixture, true);
            assertThat(fixture.remoteApi.verificationCalls).isEqualTo(1);
        } finally {
            if (first.isOpen()) {
                first.finishAndReleaseAll();
            }
            if (reconnect != null) {
                reconnect.finishAndReleaseAll();
            }
        }
    }

    @Test
    void identityRejectsLegacyWorkerGroupPayloadBeforeVerification() {
        Fixture fixture = new Fixture();
        EmbeddedChannel channel = fixture.channel();
        try {
            channel.writeInbound(fixture.identity(
                    "worker-1",
                    Jsons.toJson(Map.of("workerGroupId", "group-1"))
            ));

            assertThat(channel.isActive()).isFalse();
            assertThat(fixture.remoteApi.verificationCalls).isZero();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void overlappingReconnectUsesWorkerIdAsTheOnlyRouteIdentity() {
        Fixture fixture = new Fixture();
        EmbeddedChannel current = fixture.channel();
        EmbeddedChannel replacement = fixture.channel();
        try {
            current.writeInbound(fixture.identity("worker-1"));
            fixture.remoteApi.currentVerification().complete(null);
            awaitBound(fixture, current);
            fixture.reportProcess.round();
            fixture.controlReports.clear();

            replacement.writeInbound(fixture.identity("worker-1"));

            assertThat(replacement.isActive()).isTrue();
            assertThat(fixture.routes.activeChannel("worker-1"))
                    .isSameAs(replacement);
            assertThat(fixture.remoteApi.verificationCalls).isEqualTo(1);
            assertThat(fixture.controlReports).isEmpty();

            replacement.finishAndReleaseAll();
            fixture.reportProcess.round();
            assertAvailability(fixture, false);
        } finally {
            current.finishAndReleaseAll();
            if (replacement.isOpen()) {
                replacement.finishAndReleaseAll();
            }
        }
    }

    @Test
    void droppedAvailabilityEvidenceDoesNotCloseTheWorker() {
        Fixture fixture = new Fixture(1);
        EmbeddedChannel channel = fixture.channel();
        try {
            assertThat(fixture.reportProcess.ingress(List.of("occupied")))
                    .isEqualTo(
                            DeliveryReportProcess.ReportIngressStatus.ACCEPTED
                    );
            channel.writeInbound(fixture.identity("worker-1"));
            fixture.remoteApi.currentVerification().complete(null);
            awaitBound(fixture, channel);

            assertThat(channel.isActive()).isTrue();
            assertThat(fixture.network.closedChannels).isEmpty();
        } finally {
            channel.finishAndReleaseAll();
        }
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
            fixture.reportProcess.round();
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
            fixture.reportProcess.round();

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

    @Test
    void connectionSnapshotReportsCurrentRouteStates() {
        Fixture fixture = new Fixture();
        EmbeddedChannel active = new EmbeddedChannel();
        EmbeddedChannel pending = new EmbeddedChannel();
        EmbeddedChannel inactive = new EmbeddedChannel();
        try {
            fixture.routes.admitIdentity("active", active);
            fixture.routes.completeVerificationAndActivate(
                    "active",
                    active
            );
            fixture.routes.admitIdentity("pending", pending);
            fixture.routes.admitIdentity("inactive", inactive);
            fixture.routes.completeVerificationAndActivate(
                    "inactive",
                    inactive
            );
            inactive.close();

            assertThat(fixture.mechanism.connectionStates(List.of(
                    "active",
                    "pending",
                    "inactive",
                    "unknown"
            ))).containsExactly(
                    Map.entry("active", WorkerConnectionState.CONNECTED),
                    Map.entry("pending", WorkerConnectionState.VERIFYING),
                    Map.entry("inactive", WorkerConnectionState.DISCONNECTED),
                    Map.entry("unknown", WorkerConnectionState.UNKNOWN)
            );
        } finally {
            active.finishAndReleaseAll();
            pending.finishAndReleaseAll();
            inactive.finishAndReleaseAll();
        }
    }

    @Test
    void closeCurrentDetachesOnlyCurrentRouteAndPreservesVerification() {
        Fixture fixture = new Fixture();
        EmbeddedChannel active = new EmbeddedChannel();
        EmbeddedChannel reconnect = new EmbeddedChannel();
        try {
            fixture.routes.admitIdentity("worker-1", active);
            fixture.routes.completeVerificationAndActivate(
                    "worker-1",
                    active
            );

            assertThat(fixture.mechanism.closeCurrentConnections(List.of(
                    "worker-1",
                    "unknown"
            ))).containsExactly(
                    Map.entry("worker-1", CloseCurrentOutcome.CLOSE_STARTED),
                    Map.entry("unknown", CloseCurrentOutcome.NOT_CONNECTED)
            );
            assertThat(fixture.network.closedChannels)
                    .containsExactly(active);
            assertThat(fixture.network.closeReasons).containsExactly(
                    AdapterConnectionCloseReason.CONTROL_REQUEST
            );
            assertThat(fixture.routes.activeChannel("worker-1")).isNull();
            assertThat(fixture.mechanism.connectionStates(List.of(
                    "worker-1"
            ))).containsExactly(Map.entry(
                    "worker-1",
                    WorkerConnectionState.DISCONNECTED
            ));

            assertThat(fixture.routes.admitIdentity(
                    "worker-1",
                    reconnect
            ).kind()).isEqualTo(
                    WorkerRouteRegistry.IdentityAdmissionKind
                            .VERIFIED_ACTIVATED
            );
            fixture.routes.onChannelClosed(active);
            assertThat(fixture.routes.activeChannel("worker-1"))
                    .isSameAs(reconnect);
        } finally {
            active.finishAndReleaseAll();
            reconnect.finishAndReleaseAll();
        }
    }

    @Test
    void inactiveCallbackCleansRouteAndPropagatesThroughPipeline() {
        Fixture fixture = new Fixture();
        AtomicInteger inactiveEvents = new AtomicInteger();
        EmbeddedChannel channel = fixture.channel(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelInactive(ChannelHandlerContext context) {
                inactiveEvents.incrementAndGet();
                context.fireChannelInactive();
            }
        });
        channel.writeInbound(fixture.identity("worker-1"));
        fixture.remoteApi.currentVerification().complete(null);
        awaitBound(fixture, channel);

        channel.finishAndReleaseAll();

        assertThat(fixture.routes.activeChannel("worker-1")).isNull();
        assertThat(inactiveEvents).hasValue(1);
    }

    @Test
    void failureCallbackCleansRouteAndUsesPhysicalServerClose() {
        Fixture fixture = new Fixture();
        EmbeddedChannel channel = fixture.channel();
        channel.writeInbound(fixture.identity("worker-1"));
        fixture.remoteApi.currentVerification().complete(null);
        awaitBound(fixture, channel);

        IllegalStateException failure = new IllegalStateException("boom");
        channel.pipeline().fireExceptionCaught(failure);

        assertThat(fixture.routes.activeChannel("worker-1")).isNull();
        assertThat(fixture.network.closedChannels).containsExactly(channel);
        assertThat(fixture.network.closeReasons).containsExactly(
                AdapterConnectionCloseReason.TRANSPORT_ERROR
        );
        channel.finishAndReleaseAll();
    }

    @Test
    void systemResultUsesControlLaneWithoutClosingOnBackpressure() {
        Fixture fixture = new Fixture(1);
        EmbeddedChannel channel = fixture.channel();
        try {
            channel.writeInbound(fixture.identity("worker-1"));
            fixture.remoteApi.currentVerification().complete(null);
            awaitBound(fixture, channel);
            fixture.reportProcess.round();
            fixture.controlReports.clear();

            channel.writeInbound(fixture.systemResult("worker-1"));
            fixture.reportProcess.round();
            assertThat(fixture.controlReports)
                    .containsExactly(fixture.systemResult("worker-1"));

            assertThat(fixture.reportProcess.ingress(List.of("occupied")))
                    .isEqualTo(
                    DeliveryReportProcess.ReportIngressStatus.ACCEPTED
            );
            channel.writeInbound(fixture.systemResult("worker-1"));

            assertThat(channel.isActive()).isTrue();
            assertThat(fixture.network.closedChannels).isEmpty();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void taskResultStillClosesCurrentChannelWhenTaskLaneIsFull() {
        Fixture fixture = new Fixture(1);
        EmbeddedChannel channel = fixture.channel();
        channel.writeInbound(fixture.identity("worker-1"));
        fixture.remoteApi.currentVerification().complete(null);
        awaitBound(fixture, channel);
        fixture.reportProcess.round();
        fixture.controlReports.clear();
        assertThat(fixture.reportProcess.ingress(List.of("occupied")))
                .isEqualTo(DeliveryReportProcess.ReportIngressStatus.ACCEPTED);

        channel.writeInbound(fixture.taskResult("worker-1"));

        assertThat(channel.isActive()).isFalse();
        assertThat(fixture.network.closeReasons).containsExactly(
                AdapterConnectionCloseReason.RESULT_BUFFER_FULL
        );
        channel.finishAndReleaseAll();
    }

    @Test
    void currentConnectedPropertiesResultUpdatesObservationAndStillForwards() {
        Fixture fixture = new Fixture();
        EmbeddedChannel channel = fixture.channel();
        try {
            channel.writeInbound(fixture.identity("worker-1"));
            fixture.remoteApi.currentVerification().complete(null);
            awaitBound(fixture, channel);
            fixture.reportProcess.round();
            fixture.controlReports.clear();

            String encoded = fixture.propertiesResult(
                    "worker-1",
                    "200",
                    "{\"properties\":{\"battery\":87}}"
            );
            channel.writeInbound(encoded);

            var snapshot = fixture.mechanism.workerObservations(
                    List.of("worker-1")
            ).get("worker-1");
            assertThat(snapshot.connectionState()).isEqualTo(
                    WorkerConnectionState.CONNECTED
            );
            assertThat(snapshot.propertiesFreshness()).isEqualTo(
                    WorkerObservationSnapshot.PropertiesFreshness.FRESH
            );
            assertThat(snapshot.propertiesVersion().revision()).isEqualTo(1L);
            assertThat(snapshot.properties()).containsEntry("battery", 87L);

            fixture.reportProcess.round();
            assertThat(fixture.controlReports).containsExactly(encoded);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void staleChannelMalformedPayloadAndWorkerFailureDoNotUpdateObservation() {
        Fixture fixture = new Fixture();
        EmbeddedChannel first = fixture.channel();
        EmbeddedChannel replacement = fixture.channel();
        try {
            first.writeInbound(fixture.identity("worker-1"));
            fixture.remoteApi.currentVerification().complete(null);
            awaitBound(fixture, first);
            fixture.reportProcess.round();
            fixture.controlReports.clear();

            replacement.writeInbound(fixture.identity("worker-1"));
            awaitBound(fixture, replacement);

            String stale = fixture.propertiesResult(
                    "worker-1",
                    "200",
                    "{\"properties\":{\"battery\":1}}"
            );
            first.writeInbound(stale);
            replacement.writeInbound(fixture.propertiesResult(
                    "worker-1",
                    "200",
                    "{\"unexpected\":true}"
            ));
            replacement.writeInbound(fixture.propertiesResult(
                    "worker-1",
                    "3303",
                    "{\"properties\":{\"battery\":2}}"
            ));

            var snapshot = fixture.mechanism.workerObservations(
                    List.of("worker-1")
            ).get("worker-1");
            assertThat(snapshot.propertiesFreshness()).isEqualTo(
                    WorkerObservationSnapshot.PropertiesFreshness.UNKNOWN
            );
            assertThat(snapshot.properties()).isNull();

            fixture.reportProcess.round();
            assertThat(fixture.controlReports).hasSize(3);
        } finally {
            first.finishAndReleaseAll();
            replacement.finishAndReleaseAll();
        }
    }

    @Test
    void disconnectRetainsPropertiesAndClearDropsBothRouteAndObservation() {
        Fixture fixture = new Fixture();
        EmbeddedChannel channel = fixture.channel();
        channel.writeInbound(fixture.identity("worker-1"));
        fixture.remoteApi.currentVerification().complete(null);
        awaitBound(fixture, channel);
        channel.writeInbound(fixture.propertiesResult(
                "worker-1",
                "200",
                "{\"properties\":{\"battery\":87}}"
        ));

        channel.finishAndReleaseAll();
        var disconnected = fixture.mechanism.workerObservations(
                List.of("worker-1")
        ).get("worker-1");
        assertThat(disconnected.connectionState()).isEqualTo(
                WorkerConnectionState.DISCONNECTED
        );
        assertThat(disconnected.properties()).containsEntry("battery", 87L);

        fixture.mechanism.clear();
        var cleared = fixture.mechanism.workerObservations(
                List.of("worker-1")
        ).get("worker-1");
        assertThat(cleared.connectionState()).isEqualTo(
                WorkerConnectionState.UNKNOWN
        );
        assertThat(cleared.propertiesFreshness()).isEqualTo(
                WorkerObservationSnapshot.PropertiesFreshness.UNKNOWN
        );
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

    private static void assertAvailability(
            Fixture fixture,
            boolean expected
    ) {
        assertThat(fixture.controlReports).singleElement()
                .satisfies(encoded -> {
                    DeliveryReport report = fixture.codec
                            .decodeDeliveryReport(encoded);
                    assertThat(report.messageType()).isEqualTo(
                            "platform.adapter.worker-availability.changed"
                    );
                    assertThat(report.forward()).isEqualTo(
                            "worker-change:v1"
                    );
                    assertThat(Jsons.parseObject(report.payload()))
                            .containsExactlyInAnyOrderEntriesOf(Map.of(
                                    "workerId", "worker-1",
                                    "available", expected
                            ));
                });
    }

    private final class Fixture {

        private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        private final PendingRouteHttpPeer remoteApi =
                new PendingRouteHttpPeer();
        private final List<String> reports = new ArrayList<>();
        private final List<String> controlReports = new ArrayList<>();
        private final ScriptedHttpServer reportServer;
        private final DeliveryReportProcess reportProcess;
        private final WorkerRouteRegistry routes = new WorkerRouteRegistry();
        private final FakeNetworkServer network = new FakeNetworkServer();
        private final WorkerConnectionMechanism mechanism;
        private final WorkerConnectionInboundHandler inboundHandler;

        private Fixture() {
            this(10);
        }

        private Fixture(int reportCapacity) {
            reportServer = reportServer();
            reportProcess = new DeliveryReportProcess(
                    new DeliveryReportRemoteApi(client(reportServer)),
                    "adapter-1",
                    reportCapacity
            );
            mechanism = new WorkerConnectionMechanism(
                        routes,
                        network,
                        new WorkerRouteRemoteApi(client(remoteApi.server)),
                        codec,
                        reportProcess,
                        "adapter-1",
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(5)
                );
            inboundHandler = new WorkerConnectionInboundHandler(mechanism);
        }

        private EmbeddedChannel channel() {
            return new EmbeddedChannel(inboundHandler);
        }

        private EmbeddedChannel channel(ChannelHandler nextHandler) {
            return new EmbeddedChannel(inboundHandler, nextHandler);
        }

        private ScriptedHttpServer reportServer() {
            ScriptedHttpServer server = new ScriptedHttpServer(request -> {
                Map<String, Object> body = Jsons.parseObject(request.body());
                @SuppressWarnings("unchecked")
                List<String> batch = (List<String>) body.get("results");
                for (String encoded : batch) {
                    if (codec.decodeDeliveryReport(encoded).dst() == SYSTEM) {
                        controlReports.add(encoded);
                    } else {
                        reports.add(encoded);
                    }
                }
                return new Response(202, Jsons.toJson(Map.of(
                        "acceptedCount", batch.size(),
                        "rejectedCount", 0
                )));
            });
            httpServers.add(server);
            return server;
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

        private String systemResult(String workerId) {
            return codec.encodeDeliveryReport(DeliveryReport.create(
                    WORKER,
                    workerId,
                    SYSTEM,
                    "platform.worker.properties.snapshot",
                    "200",
                    "{}",
                    "direct-call:v1:test"
            ));
        }

        private String propertiesResult(
                String workerId,
                String outcomeCode,
                String payload
        ) {
            return codec.encodeDeliveryReport(DeliveryReport.create(
                    WORKER,
                    workerId,
                    SYSTEM,
                    "platform.worker.properties.snapshot",
                    outcomeCode,
                    payload,
                    "direct-call:v1:properties"
            ));
        }

        private String identity(String workerId) {
            return identity(workerId, "null");
        }

        private String identity(String workerId, String payload) {
            return codec.encodeDeliveryReport(DeliveryReport.create(
                    WORKER,
                    workerId,
                    ADAPTER,
                    WORKER_CONNECTION_IDENTIFY_EVENT_CODE,
                    "200",
                    payload,
                    ""
            ));
        }
    }

    private static WorkerDeliveryHttpClient client(
            ScriptedHttpServer server
    ) {
        return new WorkerDeliveryHttpClient(
                server.baseUri(),
                Duration.ofSeconds(2)
        );
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
        private final List<Channel> closedChannels = new ArrayList<>();
        private final List<AdapterConnectionCloseReason> closeReasons =
                new ArrayList<>();

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
            closedChannels.add(channel);
            closeReasons.add(reason);
            if (reason != AdapterConnectionCloseReason.REPLACED) {
                channel.close();
            }
        }

        @Override
        public void close() {
        }
    }
}
