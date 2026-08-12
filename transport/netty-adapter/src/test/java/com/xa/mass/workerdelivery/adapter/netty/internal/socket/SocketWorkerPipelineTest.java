package com.xa.mass.workerdelivery.adapter.netty.internal.socket;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.netty.internal.gateway.BoundedDeliveryReportQueue;
import com.xa.mass.workerdelivery.adapter.netty.internal.gateway.DeliveryReportPump;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class SocketWorkerPipelineTest {

    @Test
    void successfulVerificationReplacesIdentityWithBoundHandler() {
        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        SocketWorkerRouteDirectory routes =
                new SocketWorkerRouteDirectory(codec);
        PendingVerificationGateway gateway =
                new PendingVerificationGateway();
        BoundedDeliveryReportQueue reportQueue =
                new BoundedDeliveryReportQueue(4);
        SocketWorkerIdentityHandler identity =
                new SocketWorkerIdentityHandler(
                        routes,
                        codec,
                        reportQueue,
                        gateway,
                        "socket-1",
                        Duration.ofSeconds(1),
                        () -> true
                );
        EmbeddedChannel channel = new EmbeddedChannel(identity);
        try {
            channel.writeInbound(encodeIdentity(codec, "worker-1"));

            assertThat(channel.config().isAutoRead()).isTrue();
            assertThat(channel.pipeline().context(
                    SocketWorkerIdentityHandler.class
            )).isNotNull();

            gateway.verification.complete(null);
            channel.runPendingTasks();

            assertThat(channel.pipeline().context(
                    SocketWorkerIdentityHandler.class
            )).isNull();
            assertThat(channel.pipeline().context(
                    SocketBoundWorkerHandler.class
            )).isNotNull();
            assertThat(routes.activeConnectionCount()).isEqualTo(1);
            assertThat(routes.verifiedWorkerCount()).isEqualTo(1);
        } finally {
            channel.finishAndReleaseAll();
        }
        assertThat(routes.activeConnectionCount()).isZero();
    }

    @Test
    void messagesDuringVerificationAreDroppedInsteadOfBuffered() {
        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        SocketWorkerRouteDirectory routes =
                new SocketWorkerRouteDirectory(codec);
        PendingVerificationGateway gateway =
                new PendingVerificationGateway();
        BoundedDeliveryReportQueue reportQueue =
                new BoundedDeliveryReportQueue(4);
        EmbeddedChannel channel = new EmbeddedChannel(
                new SocketWorkerIdentityHandler(
                        routes,
                        codec,
                        reportQueue,
                        gateway,
                        "socket-1",
                        Duration.ofSeconds(1),
                        () -> true
                )
        );
        try {
            channel.writeInbound(encodeIdentity(codec, "worker-1"));
            channel.writeInbound(encodeTaskResult(codec, "worker-1"));

            gateway.verification.complete(null);
            channel.runPendingTasks();
            new DeliveryReportPump(
                    gateway,
                    "socket-1",
                    reportQueue
            ).run();

            assertThat(gateway.appendedResults).isEmpty();
            assertThat(routes.activeConnectionCount()).isEqualTo(1);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void verifiedRouteSurvivesDisconnectAndNewDirectoryVerifiesAgain() {
        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        PendingVerificationGateway gateway =
                new PendingVerificationGateway();
        BoundedDeliveryReportQueue reportQueue =
                new BoundedDeliveryReportQueue(4);
        SocketWorkerRouteDirectory routes =
                new SocketWorkerRouteDirectory(codec);
        EmbeddedChannel first = identityChannel(
                routes,
                codec,
                reportQueue,
                gateway
        );
        first.writeInbound(encodeIdentity(codec, "worker-1"));
        gateway.verification.complete(null);
        first.runPendingTasks();
        assertThat(gateway.verificationCalls).isEqualTo(1);
        first.finishAndReleaseAll();
        assertThat(routes.activeConnectionCount()).isZero();
        assertThat(routes.verifiedWorkerCount()).isEqualTo(1);

        EmbeddedChannel reconnect = identityChannel(
                routes,
                codec,
                reportQueue,
                gateway
        );
        try {
            reconnect.writeInbound(encodeIdentity(codec, "worker-1"));
            assertThat(gateway.verificationCalls).isEqualTo(1);
            assertThat(reconnect.pipeline().context(
                    SocketBoundWorkerHandler.class
            )).isNotNull();
        } finally {
            reconnect.finishAndReleaseAll();
        }

        SocketWorkerRouteDirectory restartedRoutes =
                new SocketWorkerRouteDirectory(codec);
        EmbeddedChannel afterRestart = identityChannel(
                restartedRoutes,
                codec,
                reportQueue,
                gateway
        );
        try {
            afterRestart.writeInbound(encodeIdentity(codec, "worker-1"));
            afterRestart.runPendingTasks();
            assertThat(gateway.verificationCalls).isEqualTo(2);
            assertThat(restartedRoutes.activeConnectionCount())
                    .isEqualTo(1);
        } finally {
            afterRestart.finishAndReleaseAll();
        }
    }

    @Test
    void firstPendingConnectionWinsAndDisconnectAllowsRetry() {
        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        PendingVerificationGateway gateway =
                new PendingVerificationGateway();
        BoundedDeliveryReportQueue reportQueue =
                new BoundedDeliveryReportQueue(4);
        SocketWorkerRouteDirectory routes =
                new SocketWorkerRouteDirectory(codec);
        EmbeddedChannel first = identityChannel(
                routes,
                codec,
                reportQueue,
                gateway
        );
        EmbeddedChannel second = identityChannel(
                routes,
                codec,
                reportQueue,
                gateway
        );
        first.writeInbound(encodeIdentity(codec, "worker-1"));
        second.writeInbound(encodeIdentity(codec, "worker-1"));

        assertThat(gateway.verificationCalls).isEqualTo(1);
        assertThat(second.isActive()).isFalse();
        assertThat(routes.pendingVerificationCount()).isEqualTo(1);
        second.finishAndReleaseAll();
        first.finishAndReleaseAll();
        assertThat(routes.pendingVerificationCount()).isZero();

        EmbeddedChannel retry = identityChannel(
                routes,
                codec,
                reportQueue,
                gateway
        );
        try {
            retry.writeInbound(encodeIdentity(codec, "worker-1"));
            assertThat(gateway.verificationCalls).isEqualTo(2);

            gateway.verification.complete(null);
            first.runPendingTasks();
            retry.runPendingTasks();

            assertThat(routes.activeConnectionCount()).isEqualTo(1);
            assertThat(routes.verifiedWorkerCount()).isEqualTo(1);
        } finally {
            retry.finishAndReleaseAll();
        }
    }

    @Test
    void resultAcceptedBeforeReplacementRemainsEligibleEvidence() {
        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        PendingVerificationGateway gateway =
                new PendingVerificationGateway();
        BoundedDeliveryReportQueue reportQueue =
                new BoundedDeliveryReportQueue(4);
        SocketWorkerRouteDirectory routes =
                new SocketWorkerRouteDirectory(codec);
        EmbeddedChannel oldChannel = new EmbeddedChannel(
                new SocketBoundWorkerHandler(
                        routes,
                        codec,
                        reportQueue,
                        "worker-1"
                )
        );
        EmbeddedChannel replacement = new EmbeddedChannel();
        String encodedResult = encodeTaskResult(codec, "worker-1");
        try {
            assertThat(routes.beginVerification("worker-1", oldChannel))
                    .isTrue();
            assertThat(routes.completeVerificationAndActivate(
                    "worker-1",
                    oldChannel
            )).isTrue();
            oldChannel.writeInbound(encodedResult);

            assertThat(routes.activateIfVerified(
                    "worker-1",
                    replacement
            )).isTrue();
            new DeliveryReportPump(
                    gateway,
                    "socket-1",
                    reportQueue
            ).run();

            assertThat(gateway.appendedResults)
                    .containsExactly(List.of(encodedResult));
        } finally {
            oldChannel.finishAndReleaseAll();
            replacement.finishAndReleaseAll();
        }
    }

    private static EmbeddedChannel identityChannel(
            SocketWorkerRouteDirectory routes,
            WorkerDeliveryCodec codec,
            BoundedDeliveryReportQueue reportQueue,
            WorkerDeliveryGatewayClient gateway
    ) {
        return new EmbeddedChannel(new SocketWorkerIdentityHandler(
                routes,
                codec,
                reportQueue,
                gateway,
                "socket-1",
                Duration.ofSeconds(1),
                () -> true
        ));
    }

    private static String encodeTaskResult(
            WorkerDeliveryCodec codec,
            String workerId
    ) {
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

    private static String encodeIdentity(
            WorkerDeliveryCodec codec,
            String workerId
    ) {
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

    private static final class PendingVerificationGateway
            implements WorkerDeliveryGatewayClient {

        private final CompletableFuture<Void> verification =
                new CompletableFuture<>();
        private final List<List<String>> appendedResults = new ArrayList<>();
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
            return verification;
        }
    }
}
