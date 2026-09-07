package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.KERNEL;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.SYSTEM;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.github.benmanes.caffeine.cache.Ticker;
import com.xa.mass.workerdelivery.adapter.application.WorkerRouteVerifier;
import com.xa.mass.workerdelivery.adapter.application.WorkerRouteVerifier.Decision;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer.Response;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.AdapterConnectionCloseReason;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.NettyWorkerServer;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.TextWriteAttempt;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism.DeliveryAttempt;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism.CloseCurrentOutcome;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportDispatcher;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.WorkerDeliveryRemoteApi;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkerConnectionMechanismTest {

    private final List<ScriptedHttpServer> httpServers = new ArrayList<>();

    @AfterEach
    void closeHttpServers() {
        httpServers.forEach(ScriptedHttpServer::close);
    }

    @Test
    void successfulVerificationMakesClaimedRouteDeliverable() {
        Fixture fixture = new Fixture();
        EmbeddedChannel channel = fixture.channel();
        try {
            channel.writeInbound(fixture.identity("worker-1"));
            assertThat(fixture.routes.claimedWorkerId(channel))
                    .isEqualTo("worker-1");
            assertThat(fixture.routes.hasVerificationEvidence("worker-1"))
                    .isFalse();

            fixture.routeVerifier.currentVerification().complete(Decision.VERIFIED);
            awaitBound(fixture, channel);

            assertThat(fixture.routes.hasVerificationEvidence("worker-1"))
                    .isTrue();
            assertThat(fixture.routes.activeChannel("worker-1"))
                    .isSameAs(channel);

            fixture.flushReports();
            assertThat(fixture.evidenceReports).hasSize(1);
            fixture.assertConnectionEvidence(
                    fixture.evidenceReports.get(0),
                    "worker-1",
                    "CONNECTED"
            );
        } finally {
            channel.finishAndReleaseAll();
        }
        assertThat(fixture.routes.activeChannel("worker-1")).isNull();
    }

    @Test
    void requestsExactlyOneBaselineOnlyAfterEachVerifiedActivation() {
        Fixture fixture = new Fixture();
        EmbeddedChannel first = fixture.channel();
        EmbeddedChannel replacement = fixture.channel();
        try {
            first.writeInbound(fixture.identity("worker-1"));
            assertThat(fixture.network.writtenMessages).isEmpty();
            fixture.routeVerifier.currentVerification().complete(Decision.VERIFIED);
            awaitBound(fixture, first);
            assertThat(fixture.network.writtenMessages).hasSize(1);
            DeliveryCommand request = fixture.codec.decodeDeliveryCommand(fixture.network.writtenMessages.get(0));
            assertThat(request.src()).isEqualTo(ADAPTER);
            assertThat(request.dst()).isEqualTo(WORKER);
            assertThat(request.messageType()).isEqualTo("platform.worker.properties.snapshot");
            assertThat(request.payload()).isEqualTo("null");
            assertThat(request.forward()).isEmpty();
            first.writeInbound(fixture.identity("worker-1"));
            assertThat(fixture.network.writtenMessages).hasSize(1);
            fixture.network.nextWriteAttempt = TextWriteAttempt.UNKNOWN;
            replacement.writeInbound(fixture.identity("worker-1"));
            assertThat(fixture.network.writeCalls).isEqualTo(2);
            assertThat(fixture.routeVerifier.verificationCalls).isEqualTo(1);
            assertThat(replacement.isActive()).isTrue();
            assertThat(fixture.routes.activeChannel("worker-1")).isSameAs(replacement);
            replacement.runPendingTasks();
            assertThat(fixture.network.writeCalls).isEqualTo(2); // No compensation/retry.
        } finally {
            first.finishAndReleaseAll();
            replacement.finishAndReleaseAll();
        }
    }

    @Test
    void lateVerificationCannotRequestBaselineOnClosedChannel() {
        Fixture fixture = new Fixture();
        EmbeddedChannel channel = fixture.channel();
        channel.writeInbound(fixture.identity("worker-1"));
        channel.close();
        fixture.routeVerifier.currentVerification().complete(Decision.VERIFIED);
        channel.runPendingTasks();
        assertThat(fixture.network.writtenMessages).isEmpty();
        assertThat(fixture.routes.activeChannel("worker-1")).isNull();
        channel.finishAndReleaseAll();
    }

    @Test
    void reportedPatchMergesOnlyWithCompleteBaselineAndNeverEntersReportQueues() {
        Fixture fixture = new Fixture();
        EmbeddedChannel channel = fixture.channel();
        try {
            String patch = fixture.propertiesPatch("worker-1", "200",
                    "{\"set\":{\"network.type\":\"cellular\",\"empty\":\"\"},\"remove\":[\"old\"]}");
            channel.writeInbound(patch); // Before identity: no close or verification.
            assertThat(channel.isActive()).isTrue();
            assertThat(fixture.routeVerifier.verificationCalls).isZero();
            channel.writeInbound(fixture.identity("worker-1"));
            channel.writeInbound(patch); // Pending verification: not buffered.
            fixture.routeVerifier.currentVerification().complete(Decision.VERIFIED);
            awaitBound(fixture, channel);
            channel.writeInbound(patch); // Verified but no complete baseline.
            assertThat(fixture.mechanism.workerProperties(List.of("worker-1"))
                    .get("worker-1").properties()).isNull();
            String baseline = fixture.propertiesFull("worker-1", "200",
                    "{\"properties\":{\"battery\":\"87\",\"old\":\"true\",\"network.type\":\"wifi\"}}");
            channel.writeInbound(baseline);
            channel.writeInbound(patch);
            var observation = fixture.mechanism.workerProperties(List.of("worker-1")).get("worker-1");
            assertThat(observation.properties()).containsOnlyKeys("battery", "network.type", "empty")
                    .containsEntry("battery", "87")
                    .containsEntry("network.type", "cellular")
                    .containsEntry("empty", "");
            channel.writeInbound(fixture.propertiesPatch("worker-1", "200", "{\"set\":{},\"remove\":[]}"));
            assertThat(fixture.mechanism.workerProperties(List.of("worker-1"))
                    .get("worker-1").updatedAtMillis()).isGreaterThan(observation.updatedAtMillis());
            fixture.flushReports();
            assertThat(fixture.systemReports).isEmpty();
            assertThat(fixture.reportQueues.get(TASK)).isEmpty();
            assertThat(fixture.reportQueues.get(SYSTEM)).isEmpty();
            assertThat(fixture.evidenceReports).hasSize(1); // Only CONNECTED evidence.
            assertThat(fixture.network.closedChannels).isEmpty();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void staleOrInvalidPropertyReportsCannotMutateBaselineOrCloseChannel() {
        Fixture fixture = new Fixture();
        EmbeddedChannel old = fixture.channel();
        EmbeddedChannel current = fixture.channel();
        try {
            old.writeInbound(fixture.identity("worker-1"));
            fixture.routeVerifier.currentVerification().complete(Decision.VERIFIED);
            awaitBound(fixture, old);
            old.writeInbound(fixture.propertiesFull("worker-1", "200", "{\"properties\":{\"battery\":\"87\"}}"));
            current.writeInbound(fixture.identity("worker-1"));
            var baseline = fixture.mechanism.workerProperties(List.of("worker-1")).get("worker-1");
            String validPatch = "{\"set\":{\"battery\":\"1\"},\"remove\":[]}";
            old.writeInbound(fixture.propertiesPatch("worker-1", "200", validPatch));
            current.writeInbound(fixture.propertiesPatch("other-worker", "200", validPatch));
            current.writeInbound(fixture.propertiesPatch("worker-1", "3301", validPatch));
            current.writeInbound(fixture.codec.encodeDeliveryReport(DeliveryReport.create(
                    ADAPTER, "worker-1", ADAPTER, "platform.worker.properties.reported", "200", validPatch, "")));
            for (String invalid : List.of(
                    "{\"properties\":{\"battery\":87}}",
                    "{\"properties\":{\"a\":null}}",
                    "{\"properties\":{\" \":\"x\"}}",
                    "{\"properties\":{\"a\":{}}}",
                    "{\"properties\":{},\"set\":{},\"remove\":[]}",
                    "{\"set\":{\"a\":true},\"remove\":[]}",
                    "{\"set\":{\"a\":[]},\"remove\":[]}",
                    "not-json", "[]", "{}", "{\"set\":{},\"remove\":[],\"extra\":true}",
                    "{\"set\":[],\"remove\":[]}", "{\"set\":{},\"remove\":{}}",
                    "{\"set\":{},\"remove\":[null]}", "{\"set\":{},\"remove\":[1]}",
                    "{\"set\":{},\"remove\":[\"battery\",\"battery\"]}",
                    "{\"set\":{\"battery\":\"1\"},\"remove\":[\"battery\"]}")) {
                current.writeInbound(fixture.propertiesPatch("worker-1", "200", invalid));
            }
            assertThat(fixture.mechanism.workerProperties(List.of("worker-1")).get("worker-1"))
                    .isEqualTo(baseline);
            assertThat(current.isActive()).isTrue();
            assertThat(fixture.network.closeReasons).containsExactly(AdapterConnectionCloseReason.REPLACED);
            assertThat(fixture.reportQueues.get(SYSTEM)).isEmpty();
            assertThat(fixture.reportQueues.get(KERNEL)).hasSize(1);
            assertThat(fixture.reportQueues.get(TASK)).isEmpty();
        } finally {
            old.finishAndReleaseAll();
            current.finishAndReleaseAll();
        }
    }

    @Test
    void rejectedVerificationSendsTerminalCloseBeforeClosingChannel() {
        Fixture fixture = new Fixture();
        EmbeddedChannel channel = fixture.channel();
        try {
            channel.writeInbound(fixture.identity("worker-1"));
            fixture.routeVerifier.currentVerification().complete(
                    Decision.REJECTED
            );
            channel.runPendingTasks();

            assertThat(channel.isActive()).isFalse();
            assertThat(fixture.routes.activeChannel("worker-1")).isNull();
            assertThat(fixture.network.writtenMessages).hasSize(1);
            DeliveryCommand close = fixture.codec.decodeDeliveryCommand(
                    fixture.network.writtenMessages.get(0)
            );
            assertThat(close.messageType()).isEqualTo(
                    "worker.connection.close"
            );
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void unavailableVerificationOnlyClosesThePhysicalConnection() {
        Fixture fixture = new Fixture();
        EmbeddedChannel channel = fixture.channel();
        try {
            channel.writeInbound(fixture.identity("worker-1"));
            fixture.routeVerifier.currentVerification()
                    .completeExceptionally(new IllegalStateException(
                            "binding unavailable"
                    ));
            channel.runPendingTasks();

            assertThat(fixture.routes.activeChannel("worker-1")).isNull();
            assertThat(fixture.network.writtenMessages).isEmpty();
            assertThat(fixture.network.closedChannels).containsExactly(
                    channel
            );
            assertThat(fixture.network.closeReasons).containsExactly(
                    AdapterConnectionCloseReason.VERIFICATION_FAILED
            );
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void disconnectedReconnectReportsOnlyExactAvailabilityTransitions() {
        Fixture fixture = new Fixture();
        EmbeddedChannel first = fixture.channel();
        EmbeddedChannel replacement = fixture.channel();
        EmbeddedChannel reconnect = fixture.channel();
        try {
            first.writeInbound(fixture.identity("worker-1"));
            fixture.routeVerifier.currentVerification().complete(Decision.VERIFIED);
            awaitBound(fixture, first);

            replacement.writeInbound(fixture.identity("worker-1"));
            awaitBound(fixture, replacement);
            first.finishAndReleaseAll();
            fixture.flushReports();
            assertThat(fixture.evidenceReports).hasSize(1);

            replacement.finishAndReleaseAll();
            reconnect.writeInbound(fixture.identity("worker-1"));
            awaitBound(fixture, reconnect);
            fixture.flushReports();

            assertThat(fixture.evidenceReports).hasSize(3);
            fixture.assertConnectionEvidence(
                    fixture.evidenceReports.get(0),
                    "worker-1",
                    "CONNECTED"
            );
            fixture.assertConnectionEvidence(
                    fixture.evidenceReports.get(1),
                    "worker-1",
                    "DISCONNECTED"
            );
            fixture.assertConnectionEvidence(
                    fixture.evidenceReports.get(2),
                    "worker-1",
                    "CONNECTED"
            );
            assertThat(fixture.routeVerifier.verificationCalls).isEqualTo(1);
        } finally {
            if (first.isOpen()) {
                first.finishAndReleaseAll();
            }
            if (replacement.isOpen()) {
                replacement.finishAndReleaseAll();
            }
            reconnect.finishAndReleaseAll();
        }
    }

    @Test
    void evidenceQueuePressureNeverClosesVerifiedWorkerChannel() {
        Fixture fixture = new Fixture(2);
        assertThat(fixture.ingressReports(fixture.report(
                KERNEL,
                "occupied-1"
        ))).isEqualTo(DeliveryReportDispatcher.DispatchStatus.ACCEPTED);
        assertThat(fixture.ingressReports(fixture.report(
                KERNEL,
                "occupied-2"
        ))).isEqualTo(DeliveryReportDispatcher.DispatchStatus.ACCEPTED);
        EmbeddedChannel channel = fixture.channel();
        try {
            channel.writeInbound(fixture.identity("worker-1"));
            fixture.routeVerifier.currentVerification().complete(Decision.VERIFIED);
            awaitBound(fixture, channel);

            assertThat(channel.isActive()).isTrue();
            assertThat(fixture.network.closedChannels).isEmpty();
        } finally {
            channel.finishAndReleaseAll();
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
            assertThat(fixture.routeVerifier.verificationCalls).isZero();
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
            fixture.routeVerifier.currentVerification().complete(Decision.VERIFIED);
            awaitBound(fixture, current);
            fixture.flushReports();
            fixture.systemReports.clear();

            replacement.writeInbound(fixture.identity("worker-1"));

            assertThat(replacement.isActive()).isTrue();
            assertThat(fixture.routes.activeChannel("worker-1"))
                    .isSameAs(replacement);
            assertThat(fixture.routeVerifier.verificationCalls).isEqualTo(1);
            assertThat(fixture.systemReports).isEmpty();

            replacement.finishAndReleaseAll();
            assertThat(fixture.mechanism.connectionStates(List.of(
                    "worker-1"
            ))).containsEntry(
                    "worker-1",
                    WorkerConnectionState.DISCONNECTED
            );
        } finally {
            current.finishAndReleaseAll();
            if (replacement.isOpen()) {
                replacement.finishAndReleaseAll();
            }
        }
    }

    @Test
    void messagesDuringVerificationAreDroppedInsteadOfBuffered() {
        Fixture fixture = new Fixture();
        EmbeddedChannel channel = fixture.channel();
        try {
            channel.writeInbound(fixture.identity("worker-1"));
            channel.writeInbound(fixture.taskResult("worker-1"));
            fixture.routeVerifier.currentVerification().complete(Decision.VERIFIED);
            awaitBound(fixture, channel);
            fixture.flushReports();
            assertThat(fixture.reports).isEmpty();
            assertThat(fixture.routes.activeChannel("worker-1"))
                    .isSameAs(channel);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void firstPendingConnectionWinsAndDisconnectAllowsRetry() {
        Fixture fixture = new Fixture();
        EmbeddedChannel first = fixture.channel();
        EmbeddedChannel second = fixture.channel();
        first.writeInbound(fixture.identity("worker-1"));
        second.writeInbound(fixture.identity("worker-1"));

        awaitVerificationCalls(fixture.routeVerifier, 1);
        assertThat(second.isActive()).isFalse();
        second.finishAndReleaseAll();
        first.finishAndReleaseAll();

        EmbeddedChannel retry = fixture.channel();
        try {
            retry.writeInbound(fixture.identity("worker-1"));
            awaitVerificationCalls(fixture.routeVerifier, 2);
            fixture.routeVerifier.currentVerification().complete(Decision.VERIFIED);
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

        fixture.routeVerifier.currentVerification().complete(Decision.VERIFIED);
        channel.runPendingTasks();

        assertThat(fixture.routes.activeChannel("worker-1")).isNull();
        EmbeddedChannel retry = fixture.channel();
        try {
            retry.writeInbound(fixture.identity("worker-1"));
            awaitVerificationCalls(fixture.routeVerifier, 2);
        } finally {
            retry.finishAndReleaseAll();
        }
    }

    @Test
    void oldVerifiedChannelMaySubmitInFlightResultAfterReplacement() {
        Fixture fixture = new Fixture();
        EmbeddedChannel oldChannel = fixture.channel();
        oldChannel.writeInbound(fixture.identity("worker-1"));
        fixture.routeVerifier.currentVerification().complete(Decision.VERIFIED);
        awaitBound(fixture, oldChannel);

        EmbeddedChannel replacement = fixture.channel();
        String result = fixture.taskResult("worker-1");
        try {
            replacement.writeInbound(fixture.identity("worker-1"));
            oldChannel.writeInbound(result);
            fixture.flushReports();

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
            fixture.routeVerifier.currentVerification().complete(Decision.VERIFIED);
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
                    .hasSize(2)
                    .endsWith(fixture.codec.encodeDeliveryCommand(command));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void definiteWriteFailureDetachesOnlyCurrentRouteAndReportsUnavailable() {
        Fixture fixture = new Fixture();
        EmbeddedChannel channel = fixture.channel();
        try {
            channel.writeInbound(fixture.identity("worker-1"));
            fixture.routeVerifier.currentVerification().complete(Decision.VERIFIED);
            awaitBound(fixture, channel);
            fixture.flushReports();

            fixture.network.nextWriteAttempt = TextWriteAttempt.UNKNOWN;
            DeliveryCommand command = DeliveryCommand.create(
                    TASK,
                    WORKER,
                    "test.observe",
                    2_000,
                    "{}",
                    "context"
            );
            assertThat(fixture.mechanism.deliver("worker-1", command))
                    .isEqualTo(DeliveryAttempt.UNKNOWN);
            fixture.flushReports();

            assertThat(fixture.routes.activeChannel("worker-1")).isNull();
            assertThat(fixture.evidenceReports).hasSize(2);
            fixture.assertConnectionEvidence(
                    fixture.evidenceReports.get(1),
                    "worker-1",
                    "DISCONNECTED"
            );
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void closeCurrentDetachesOnlyCurrentRouteAndPreservesVerification() {
        Fixture fixture = new Fixture();
        EmbeddedChannel active = new EmbeddedChannel();
        EmbeddedChannel reconnect = new EmbeddedChannel();
        try {
            fixture.routes.admitIdentity("worker-1", active);
            fixture.routes.completeVerification(
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
                    AdapterConnectionCloseReason.MANAGEMENT_REQUEST
            );
            assertThat(fixture.routes.activeChannel("worker-1")).isNull();
            assertThat(fixture.mechanism.connectionStates(List.of(
                    "worker-1"
            ))).containsExactly(Map.entry(
                    "worker-1",
                    WorkerConnectionState.DISCONNECTED
            ));
            fixture.flushReports();
            assertThat(fixture.evidenceReports).hasSize(1);
            fixture.assertConnectionEvidence(
                    fixture.evidenceReports.get(0),
                    "worker-1",
                    "DISCONNECTED"
            );

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
        fixture.routeVerifier.currentVerification().complete(Decision.VERIFIED);
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
        fixture.routeVerifier.currentVerification().complete(Decision.VERIFIED);
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
    void systemResultBackpressureDoesNotCloseTheWorker() {
        Fixture fixture = new Fixture(2);
        EmbeddedChannel channel = fixture.channel();
        try {
            channel.writeInbound(fixture.identity("worker-1"));
            fixture.routeVerifier.currentVerification().complete(Decision.VERIFIED);
            awaitBound(fixture, channel);
            fixture.flushReports();
            fixture.systemReports.clear();

            channel.writeInbound(fixture.systemResult("worker-1"));
            fixture.flushReports();
            assertThat(fixture.systemReports)
                    .containsExactly(fixture.systemResult("worker-1"));

            assertThat(fixture.ingressReports(fixture.report(
                    SYSTEM,
                    "occupied-1"
            ))).isEqualTo(
                    DeliveryReportDispatcher.DispatchStatus.ACCEPTED
            );
            assertThat(fixture.ingressReports(fixture.report(
                    SYSTEM,
                    "occupied-2"
            ))).isEqualTo(
                    DeliveryReportDispatcher.DispatchStatus.ACCEPTED
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
        Fixture fixture = new Fixture(2);
        EmbeddedChannel channel = fixture.channel();
        channel.writeInbound(fixture.identity("worker-1"));
        fixture.routeVerifier.currentVerification().complete(Decision.VERIFIED);
        awaitBound(fixture, channel);
        fixture.flushReports();
        fixture.systemReports.clear();
        assertThat(fixture.ingressReports(fixture.report(
                TASK,
                "occupied-1"
        ))).isEqualTo(DeliveryReportDispatcher.DispatchStatus.ACCEPTED);
        assertThat(fixture.ingressReports(fixture.report(
                TASK,
                "occupied-2"
        ))).isEqualTo(DeliveryReportDispatcher.DispatchStatus.ACCEPTED);

        channel.writeInbound(fixture.taskResult("worker-1"));

        assertThat(channel.isActive()).isFalse();
        assertThat(fixture.network.closeReasons).containsExactly(
                AdapterConnectionCloseReason.RESULT_BUFFER_FULL
        );
        channel.finishAndReleaseAll();
    }

    @Test
    void fullReportUpdatesCacheWhileOrdinarySnapshotResultOnlyForwards() {
        Fixture fixture = new Fixture();
        EmbeddedChannel channel = fixture.channel();
        try {
            channel.writeInbound(fixture.identity("worker-1"));
            fixture.routeVerifier.currentVerification().complete(Decision.VERIFIED);
            awaitBound(fixture, channel);
            fixture.flushReports();
            fixture.systemReports.clear();

            String encoded = fixture.propertiesFull(
                    "worker-1",
                    "200",
                    "{\"properties\":{\"battery\":\"87\"}}"
            );
            channel.writeInbound(encoded);

            var snapshot = fixture.mechanism.workerProperties(
                    List.of("worker-1")
            ).get("worker-1");
            assertThat(snapshot.updatedAtMillis()).isNotNull();
            assertThat(snapshot.properties()).containsEntry("battery", "87");
            assertThat(fixture.mechanism.connectionStates(
                    List.of("worker-1")
            )).containsEntry("worker-1", WorkerConnectionState.CONNECTED);

            String ordinary = fixture.codec.encodeDeliveryReport(DeliveryReport.create(
                    WORKER, "worker-1", SYSTEM, "platform.worker.properties.snapshot",
                    "200", "{\"properties\":{\"battery\":\"99\"}}",
                    "direct-call:v1:properties"
            ));
            channel.writeInbound(ordinary);
            assertThat(fixture.mechanism.workerProperties(List.of("worker-1")).get("worker-1"))
                    .isEqualTo(snapshot);
            fixture.flushReports();
            assertThat(fixture.systemReports).containsExactly(ordinary);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void staleChannelMalformedPayloadAndWorkerFailureDoNotUpdateProperties() {
        Fixture fixture = new Fixture();
        EmbeddedChannel first = fixture.channel();
        EmbeddedChannel replacement = fixture.channel();
        try {
            first.writeInbound(fixture.identity("worker-1"));
            fixture.routeVerifier.currentVerification().complete(Decision.VERIFIED);
            awaitBound(fixture, first);
            fixture.flushReports();
            fixture.systemReports.clear();
            first.writeInbound(fixture.propertiesFull(
                    "worker-1",
                    "200",
                    "{\"properties\":{\"battery\":\"87\"}}"
            ));

            replacement.writeInbound(fixture.identity("worker-1"));
            awaitBound(fixture, replacement);

            String stale = fixture.propertiesFull(
                    "worker-1",
                    "200",
                    "{\"properties\":{\"battery\":\"1\"}}"
            );
            first.writeInbound(stale);
            replacement.writeInbound(fixture.propertiesFull(
                    "worker-1",
                    "200",
                    "{\"unexpected\":true}"
            ));
            replacement.writeInbound(fixture.propertiesFull(
                    "worker-1",
                    "3303",
                    "{\"properties\":{\"battery\":\"2\"}}"
            ));

            var snapshot = fixture.mechanism.workerProperties(
                    List.of("worker-1")
            ).get("worker-1");
            assertThat(snapshot.updatedAtMillis()).isNotNull();
            assertThat(snapshot.properties()).containsEntry("battery", "87");

            assertThat(fixture.reportQueues.get(SYSTEM)).isEmpty();
        } finally {
            first.finishAndReleaseAll();
            replacement.finishAndReleaseAll();
        }
    }

    @Test
    void disconnectRetainsPropertiesAndClearDropsIndependentProjections() {
        Fixture fixture = new Fixture();
        EmbeddedChannel channel = fixture.channel();
        channel.writeInbound(fixture.identity("worker-1"));
        fixture.routeVerifier.currentVerification().complete(Decision.VERIFIED);
        awaitBound(fixture, channel);
        channel.writeInbound(fixture.propertiesFull(
                "worker-1",
                "200",
                "{\"properties\":{\"battery\":\"87\"}}"
        ));
        Long updatedAtMillis = fixture.mechanism.workerProperties(
                List.of("worker-1")
        ).get("worker-1").updatedAtMillis();

        channel.finishAndReleaseAll();
        var disconnected = fixture.mechanism.workerProperties(
                List.of("worker-1")
        ).get("worker-1");
        assertThat(disconnected.properties()).containsEntry("battery", "87");
        assertThat(disconnected.updatedAtMillis()).isEqualTo(updatedAtMillis);
        assertThat(fixture.mechanism.connectionStates(
                List.of("worker-1")
        )).containsEntry("worker-1", WorkerConnectionState.DISCONNECTED);

        EmbeddedChannel reconnect = fixture.channel();
        reconnect.writeInbound(fixture.identity("worker-1"));
        awaitBound(fixture, reconnect);
        var reconnected = fixture.mechanism.workerProperties(
                List.of("worker-1")
        ).get("worker-1");
        assertThat(reconnected.updatedAtMillis()).isEqualTo(updatedAtMillis);
        assertThat(reconnected.properties()).containsEntry("battery", "87");
        assertThat(fixture.routeVerifier.verificationCalls).isEqualTo(1);

        fixture.mechanism.clear();
        var cleared = fixture.mechanism.workerProperties(
                List.of("worker-1")
        ).get("worker-1");
        assertThat(cleared.updatedAtMillis()).isNull();
        assertThat(cleared.properties()).isNull();
        assertThat(fixture.mechanism.connectionStates(
                List.of("worker-1")
        )).containsEntry("worker-1", WorkerConnectionState.UNKNOWN);
        reconnect.finishAndReleaseAll();
    }

    @Test
    void activePropertiesSurviveRetentionButDisappearWithRouteIdentity() {
        MutableTicker ticker = new MutableTicker();
        Duration retention = Duration.ofMinutes(10);
        WorkerRouteRegistry routes = new WorkerRouteRegistry(
                retention,
                100_000L,
                ticker
        );
        Fixture fixture = new Fixture(10, routes);
        EmbeddedChannel channel = fixture.channel();
        try {
            channel.writeInbound(fixture.identity("worker-1"));
            fixture.routeVerifier.currentVerification().complete(Decision.VERIFIED);
            awaitBound(fixture, channel);
            channel.writeInbound(fixture.propertiesFull(
                    "worker-1",
                    "200",
                    "{\"properties\":{\"battery\":\"87\"}}"
            ));

            ticker.advance(retention.plusNanos(1L));
            assertThat(fixture.mechanism.workerProperties(
                    List.of("worker-1")
            ).get("worker-1").properties()).containsEntry("battery", "87");

            channel.finishAndReleaseAll();
            WorkerPropertiesObservation unknown =
                    fixture.mechanism.workerProperties(
                            List.of("worker-1")
                    ).get("worker-1");
            assertThat(unknown.updatedAtMillis()).isNull();
            assertThat(unknown.properties()).isNull();
            assertThat(fixture.mechanism.connectionStates(
                    List.of("worker-1")
            )).containsEntry("worker-1", WorkerConnectionState.UNKNOWN);
        } finally {
            if (channel.isOpen()) {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void newFirstVerificationClaimDropsPreviousRouteProperties() {
        Fixture fixture = new Fixture();
        EmbeddedChannel first = fixture.channel();
        EmbeddedChannel reconnect = null;
        try {
            first.writeInbound(fixture.identity("worker-1"));
            fixture.routeVerifier.currentVerification().complete(Decision.VERIFIED);
            awaitBound(fixture, first);
            first.writeInbound(fixture.propertiesFull(
                    "worker-1",
                    "200",
                    "{\"properties\":{\"battery\":\"87\"}}"
            ));
            first.finishAndReleaseAll();
            fixture.routes.clear();

            reconnect = fixture.channel();
            reconnect.writeInbound(fixture.identity("worker-1"));
            awaitVerificationCalls(fixture.routeVerifier, 2);
            WorkerPropertiesObservation unknown =
                    fixture.mechanism.workerProperties(
                            List.of("worker-1")
                    ).get("worker-1");
            assertThat(unknown.updatedAtMillis()).isNull();
            assertThat(unknown.properties()).isNull();
            fixture.routeVerifier.currentVerification().complete(Decision.VERIFIED);
            awaitBound(fixture, reconnect);
        } finally {
            if (first.isOpen()) {
                first.finishAndReleaseAll();
            }
            if (reconnect != null) {
                reconnect.finishAndReleaseAll();
            }
        }
    }

    private static void awaitBound(
            Fixture fixture,
            EmbeddedChannel channel
    ) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            channel.runPendingTasks();
            if (fixture.routes.activeChannel(
                    fixture.routes.claimedWorkerId(channel)
            ) == channel) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Worker route did not become verified");
    }

    private static void awaitVerificationCalls(
            PendingRouteVerifier routeVerifier,
            int expected
    ) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (routeVerifier.verificationCalls >= expected) {
                assertThat(routeVerifier.verificationCalls)
                        .isEqualTo(expected);
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Route verification request did not start");
    }

    private final class Fixture {

        private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        private final PendingRouteVerifier routeVerifier =
                new PendingRouteVerifier();
        private final List<String> reports = new CopyOnWriteArrayList<>();
        private final List<String> systemReports =
                new CopyOnWriteArrayList<>();
        private final List<String> evidenceReports =
                new CopyOnWriteArrayList<>();
        private final AtomicInteger completedReportRequests =
                new AtomicInteger();
        private final ScriptedHttpServer reportServer;
        private final Map<DeliveryEndpoint, ArrayDeque<DeliveryReport>>
                reportQueues = new EnumMap<>(DeliveryEndpoint.class);
        private final DeliveryReportDispatcher reportDispatcher;
        private final int reportCapacity;
        private final WorkerRouteRegistry routes;
        private final FakeNetworkServer network = new FakeNetworkServer();
        private final WorkerConnectionMechanism mechanism;
        private final WorkerConnectionInboundHandler inboundHandler;

        private Fixture() {
            this(10);
        }

        private Fixture(int reportCapacity) {
            this(
                    reportCapacity,
                    new WorkerRouteRegistry(
                            Duration.ofMinutes(10),
                            100_000L
                    )
            );
        }

        private Fixture(
                int reportCapacity,
                WorkerRouteRegistry routes
        ) {
            this.routes = routes;
            this.reportCapacity = reportCapacity;
            reportQueues.put(TASK, new ArrayDeque<>());
            reportQueues.put(SYSTEM, new ArrayDeque<>());
            reportQueues.put(KERNEL, new ArrayDeque<>());
            reportServer = reportServer();
            reportDispatcher = reportDispatcher();
            doAnswer(invocation -> ingressReports(
                    invocation.getArgument(0)
            )).when(reportDispatcher).tryDispatch(any(DeliveryReport.class));
            mechanism = new WorkerConnectionMechanism(
                        routes,
                        network,
                        routeVerifier,
                        codec,
                        reportDispatcher,
                        "adapter-1",
                        Duration.ofSeconds(1),
                        64L * 1024L * 1024L
                );
            inboundHandler = new WorkerConnectionInboundHandler(mechanism);
        }

        private EmbeddedChannel channel() {
            return new EmbeddedChannel(inboundHandler);
        }

        private EmbeddedChannel channel(ChannelHandler nextHandler) {
            return new EmbeddedChannel(inboundHandler, nextHandler);
        }

        private void flushReports() {
            int previousRequests = completedReportRequests.get();
            remoteApi(reportServer).appendReports(
                    "adapter-1",
                    takeReports()
            );
            if (completedReportRequests.get() <= previousRequests) {
                throw new AssertionError("No queued report was submitted");
            }
        }

        private synchronized DeliveryReportDispatcher.DispatchStatus
        ingressReports(
                DeliveryReport report
        ) {
            ArrayDeque<DeliveryReport> queue = reportQueues.get(report.dst());
            if (queue == null) {
                throw new IllegalArgumentException("unsupported destination");
            }
            if (queue.size() >= reportCapacity) {
                return DeliveryReportDispatcher.DispatchStatus.FULL;
            }
            queue.addLast(report);
            return DeliveryReportDispatcher.DispatchStatus.ACCEPTED;
        }

        private synchronized List<DeliveryReport> takeReports() {
            ArrayDeque<DeliveryReport> queue = null;
            for (DeliveryEndpoint destination
                    : List.of(TASK, SYSTEM, KERNEL)) {
                ArrayDeque<DeliveryReport> candidate =
                        reportQueues.get(destination);
                if (!candidate.isEmpty()) {
                    queue = candidate;
                    break;
                }
            }
            ArrayList<DeliveryReport> batch = new ArrayList<>(100);
            while (queue != null
                    && batch.size() < 100
                    && !queue.isEmpty()) {
                batch.add(queue.removeFirst());
            }
            if (batch.isEmpty()) {
                throw new AssertionError("No queued report was available");
            }
            return List.copyOf(batch);
        }

        private DeliveryReportDispatcher reportDispatcher() {
            return mock(DeliveryReportDispatcher.class);
        }

        private ScriptedHttpServer reportServer() {
            ScriptedHttpServer server = new ScriptedHttpServer(request -> {
                List<DeliveryReport> batch = Jsons.parseArray(request.body())
                        .stream()
                        .map(value -> decodeReportObject(codec, value))
                        .toList();
                for (DeliveryReport report : batch) {
                    String encoded = codec.encodeDeliveryReport(report);
                    if (report.dst() == SYSTEM) {
                        systemReports.add(encoded);
                    } else if (report.dst() == KERNEL) {
                        evidenceReports.add(encoded);
                    } else {
                        reports.add(encoded);
                    }
                }
                completedReportRequests.incrementAndGet();
                return new Response(202, Jsons.toJson(Map.of(
                        "acceptedCount", batch.size(),
                        "rejectedCount", 0
                )));
            });
            httpServers.add(server);
            return server;
        }

        private static DeliveryReport decodeReportObject(
                WorkerDeliveryCodec codec,
                Object value
        ) {
            if (!(value instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException(
                        "Expected DeliveryReport JSON object"
                );
            }
            java.util.LinkedHashMap<String, Object> fields =
                    new java.util.LinkedHashMap<>();
            raw.forEach((key, fieldValue) -> {
                if (!(key instanceof String name)) {
                    throw new IllegalArgumentException(
                            "Expected DeliveryReport field name"
                    );
                }
                fields.put(name, fieldValue);
            });
            return codec.decodeDeliveryReport(fields);
        }

        private void assertConnectionEvidence(
                String encodedReport,
                String workerId,
                String state
        ) {
            DeliveryReport report = codec.decodeDeliveryReport(encodedReport);
            assertThat(report.src()).isEqualTo(ADAPTER);
            assertThat(report.sourceId()).isEqualTo("adapter-1");
            assertThat(report.dst()).isEqualTo(KERNEL);
            assertThat(report.messageType()).isEqualTo(
                    "platform.adapter.worker-connection.changed"
            );
            assertThat(report.outcomeCode()).isEqualTo("200");
            assertThat(report.forward()).isEqualTo(
                    "worker-serviceability-evidence:v1"
            );
            Map<String, Object> payload = Jsons.parseObject(report.payload());
            assertThat(payload).containsOnlyKeys(
                    "workerId",
                    "state",
                    "observedAtMillis"
            );
            assertThat(payload).containsEntry("workerId", workerId);
            assertThat(payload).containsEntry("state", state);
            assertThat(payload.get("observedAtMillis"))
                    .isInstanceOf(Number.class);
        }

        private DeliveryReport report(
                DeliveryEndpoint destination,
                String payload
        ) {
            DeliveryEndpoint source = destination == KERNEL
                    ? ADAPTER
                    : WORKER;
            return DeliveryReport.create(
                    source,
                    source == ADAPTER ? "adapter-1" : "worker-1",
                    destination,
                    "test.report",
                    "200",
                    payload,
                    destination == TASK ? "context" : "direct-call:v1:test"
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

        private String propertiesFull(
                String workerId,
                String outcomeCode,
                String payload
        ) {
            return codec.encodeDeliveryReport(DeliveryReport.create(
                    WORKER,
                    workerId,
                    ADAPTER,
                    "platform.worker.properties.reported",
                    outcomeCode,
                    payload,
                    ""
            ));
        }

        private String propertiesPatch(String workerId, String outcomeCode, String payload) {
            return codec.encodeDeliveryReport(DeliveryReport.create(
                    WORKER, workerId, ADAPTER, "platform.worker.properties.reported",
                    outcomeCode, payload, ""
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

    private static WorkerDeliveryRemoteApi remoteApi(
            ScriptedHttpServer server
    ) {
        return new WorkerDeliveryRemoteApi(
                server.baseUri(),
                Duration.ofSeconds(2),
                new WorkerDeliveryCodec()
        );
    }

    private static final class PendingRouteVerifier
            implements WorkerRouteVerifier {

        private volatile CompletableFuture<Decision> verification;
        private volatile int verificationCalls;

        @Override
        public CompletableFuture<Decision> verify(
                String adapterId,
                String workerId
        ) {
            CompletableFuture<Decision> current = new CompletableFuture<>();
            verification = current;
            verificationCalls++;
            return current;
        }

        private CompletableFuture<Decision> currentVerification() {
            long deadline = System.nanoTime()
                    + Duration.ofSeconds(2).toNanos();
            while (System.nanoTime() < deadline) {
                CompletableFuture<Decision> current = verification;
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
        private int writeCalls;
        private final List<Channel> closedChannels = new ArrayList<>();
        private final List<AdapterConnectionCloseReason> closeReasons =
                new ArrayList<>();
        private TextWriteAttempt nextWriteAttempt = TextWriteAttempt.STARTED;

        @Override
        public void start(ChannelHandler sharedConnectionHandler) {
        }

        @Override
        public TextWriteAttempt writeText(Channel channel, String message) {
            writeCalls++;
            if (!channel.isActive() || !channel.isWritable()) {
                return TextWriteAttempt.RETRY_LATER;
            }
            TextWriteAttempt attempt = nextWriteAttempt;
            nextWriteAttempt = TextWriteAttempt.STARTED;
            if (attempt != TextWriteAttempt.STARTED) {
                return attempt;
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

    private static final class MutableTicker implements Ticker {

        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        private void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
