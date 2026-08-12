package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.netty.internal.gateway.BoundedDeliveryReportQueue;
import com.xa.mass.workerdelivery.adapter.netty.internal.gateway.DeliveryReportPump;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.AdapterNetworkProtocol;
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

class WorkerConnectionPipelineTest {

    @Test
    void successfulVerificationReplacesIdentityWithBoundHandler() {
        Fixture fixture = new Fixture();
        EmbeddedChannel channel = fixture.identityChannel();
        try {
            channel.writeInbound(fixture.identity("worker-1"));
            assertThat(channel.config().isAutoRead()).isTrue();
            assertThat(channel.pipeline().context(WorkerIdentityHandler.class))
                    .isNotNull();

            fixture.gateway.verification.complete(null);
            channel.runPendingTasks();

            assertThat(channel.pipeline().context(WorkerIdentityHandler.class))
                    .isNull();
            assertThat(channel.pipeline().context(BoundWorkerHandler.class))
                    .isNotNull();
            assertThat(fixture.routes.activeConnectionCount()).isEqualTo(1);
            assertThat(fixture.routes.verifiedWorkerCount()).isEqualTo(1);
        } finally {
            channel.finishAndReleaseAll();
        }
        assertThat(fixture.routes.activeConnectionCount()).isZero();
    }

    @Test
    void messagesDuringVerificationAreDroppedInsteadOfBuffered() {
        Fixture fixture = new Fixture();
        EmbeddedChannel channel = fixture.identityChannel();
        try {
            channel.writeInbound(fixture.identity("worker-1"));
            channel.writeInbound(fixture.taskResult("worker-1"));
            fixture.gateway.verification.complete(null);
            channel.runPendingTasks();
            fixture.reportPump().run();

            assertThat(fixture.gateway.appendedResults).isEmpty();
            assertThat(fixture.routes.activeConnectionCount()).isEqualTo(1);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void verifiedRouteSurvivesDisconnectAndNewDirectoryVerifiesAgain() {
        Fixture fixture = new Fixture();
        EmbeddedChannel first = fixture.identityChannel();
        first.writeInbound(fixture.identity("worker-1"));
        fixture.gateway.verification.complete(null);
        first.runPendingTasks();
        assertThat(fixture.gateway.verificationCalls).isEqualTo(1);
        first.finishAndReleaseAll();
        assertThat(fixture.routes.verifiedWorkerCount()).isEqualTo(1);

        EmbeddedChannel reconnect = fixture.identityChannel();
        try {
            reconnect.writeInbound(fixture.identity("worker-1"));
            assertThat(fixture.gateway.verificationCalls).isEqualTo(1);
            assertThat(reconnect.pipeline().context(BoundWorkerHandler.class))
                    .isNotNull();
        } finally {
            reconnect.finishAndReleaseAll();
        }

        Fixture restarted = new Fixture(fixture.gateway);
        EmbeddedChannel afterRestart = restarted.identityChannel();
        try {
            afterRestart.writeInbound(restarted.identity("worker-1"));
            afterRestart.runPendingTasks();
            assertThat(fixture.gateway.verificationCalls).isEqualTo(2);
            assertThat(restarted.routes.activeConnectionCount()).isEqualTo(1);
        } finally {
            afterRestart.finishAndReleaseAll();
        }
    }

    @Test
    void firstPendingConnectionWinsAndDisconnectAllowsRetry() {
        Fixture fixture = new Fixture();
        EmbeddedChannel first = fixture.identityChannel();
        EmbeddedChannel second = fixture.identityChannel();
        first.writeInbound(fixture.identity("worker-1"));
        second.writeInbound(fixture.identity("worker-1"));

        assertThat(fixture.gateway.verificationCalls).isEqualTo(1);
        assertThat(second.isActive()).isFalse();
        second.finishAndReleaseAll();
        first.finishAndReleaseAll();
        assertThat(fixture.routes.pendingVerificationCount()).isZero();

        EmbeddedChannel retry = fixture.identityChannel();
        try {
            retry.writeInbound(fixture.identity("worker-1"));
            assertThat(fixture.gateway.verificationCalls).isEqualTo(2);
            fixture.gateway.verification.complete(null);
            retry.runPendingTasks();
            assertThat(fixture.routes.activeConnectionCount()).isEqualTo(1);
        } finally {
            retry.finishAndReleaseAll();
        }
    }

    @Test
    void resultAcceptedBeforeReplacementRemainsEligibleEvidence() {
        Fixture fixture = new Fixture();
        EmbeddedChannel oldChannel = new EmbeddedChannel(
                fixture.handlers.newBoundHandler("worker-1")
        );
        EmbeddedChannel replacement = new EmbeddedChannel();
        String encodedResult = fixture.taskResult("worker-1");
        try {
            assertThat(fixture.routes.beginVerification(
                    "worker-1",
                    oldChannel
            )).isTrue();
            assertThat(fixture.routes.completeVerificationAndActivate(
                    "worker-1",
                    oldChannel
            )).isTrue();
            oldChannel.writeInbound(encodedResult);
            assertThat(fixture.routes.activateIfVerified(
                    "worker-1",
                    replacement
            )).isTrue();
            fixture.reportPump().run();

            assertThat(fixture.gateway.appendedResults)
                    .containsExactly(List.of(encodedResult));
        } finally {
            oldChannel.finishAndReleaseAll();
            replacement.finishAndReleaseAll();
        }
    }

    private static final class Fixture {

        private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        private final PendingVerificationGateway gateway;
        private final BoundedDeliveryReportQueue reportQueue =
                new BoundedDeliveryReportQueue(4);
        private final WorkerRouteDirectory routes;
        private final WorkerConnectionHandlerFactory handlers;

        private Fixture() {
            this(new PendingVerificationGateway());
        }

        private Fixture(PendingVerificationGateway gateway) {
            this.gateway = gateway;
            routes = new WorkerRouteDirectory(
                    codec,
                    AdapterNetworkProtocol.socket(Duration.ofSeconds(1))
            );
            handlers = new WorkerConnectionHandlerFactory(
                    routes,
                    codec,
                    reportQueue,
                    gateway,
                    "adapter-1",
                    Duration.ofSeconds(1),
                    () -> true
            );
        }

        private EmbeddedChannel identityChannel() {
            return new EmbeddedChannel(handlers.newIdentityHandler());
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
