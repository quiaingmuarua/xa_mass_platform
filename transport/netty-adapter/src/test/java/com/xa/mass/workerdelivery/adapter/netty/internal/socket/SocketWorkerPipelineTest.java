package com.xa.mass.workerdelivery.adapter.netty.internal.socket;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.netty.internal.gateway.BoundedDeliveryReportQueue;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class SocketWorkerPipelineTest {

    @Test
    void successfulVerificationReplacesIdentityWithBoundHandler() {
        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        SocketBoundWorkerDirectory connections =
                new SocketBoundWorkerDirectory(codec);
        PendingVerificationGateway gateway =
                new PendingVerificationGateway();
        SocketWorkerIdentityHandler identity =
                new SocketWorkerIdentityHandler(
                        connections,
                        codec,
                        new BoundedDeliveryReportQueue(4),
                        gateway,
                        "socket-1",
                        Duration.ofSeconds(1),
                        () -> true
                );
        EmbeddedChannel channel = new EmbeddedChannel(identity);
        try {
            channel.writeInbound(encodeIdentity(codec, "worker-1"));

            assertThat(channel.config().isAutoRead()).isFalse();
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
            assertThat(channel.config().isAutoRead()).isTrue();
            assertThat(connections.activeConnectionCount()).isEqualTo(1);
        } finally {
            channel.finishAndReleaseAll();
        }
        assertThat(connections.activeConnectionCount()).isZero();
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
        }

        @Override
        public CompletionStage<Void> verifyWorkerRoute(
                String endpointManagerId,
                String workerId
        ) {
            return verification;
        }
    }
}
