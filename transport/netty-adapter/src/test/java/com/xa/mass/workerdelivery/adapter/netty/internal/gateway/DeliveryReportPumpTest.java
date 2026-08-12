package com.xa.mass.workerdelivery.adapter.netty.internal.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeliveryReportPumpTest {

    @Test
    void oneRoundSubmitsAllCurrentlyBufferedReportsOnce() {
        FakeGateway gateway = new FakeGateway();
        BoundedDeliveryReportQueue queue = new BoundedDeliveryReportQueue(4);
        queue.offer("report-1");
        queue.offer("report-2");
        DeliveryReportPump pump = new DeliveryReportPump(
                gateway,
                "adapter-1",
                queue
        );

        pump.run();
        pump.run();

        assertThat(gateway.attempts).containsExactly(
                List.of("report-1", "report-2")
        );
        assertThat(queue.isEmpty()).isTrue();
        assertThat(pump.pendingBatch()).isNull();
    }

    @Test
    void failedBatchIsRetriedBeforeNewlyBufferedReports() {
        FakeGateway gateway = new FakeGateway();
        gateway.outcomes.add(false);
        gateway.outcomes.add(true);
        BoundedDeliveryReportQueue queue = new BoundedDeliveryReportQueue(4);
        queue.offer("report-1");
        DeliveryReportPump pump = new DeliveryReportPump(
                gateway,
                "adapter-1",
                queue
        );

        pump.run();
        queue.offer("report-2");
        pump.run();

        assertThat(gateway.attempts).containsExactly(
                List.of("report-1"),
                List.of("report-1")
        );
        assertThat(pump.pendingBatch()).isNull();
        assertThat(queue.isEmpty()).isFalse();

        pump.run();
        assertThat(gateway.attempts.get(2)).containsExactly("report-2");
    }

    @Test
    void protocolFailureDropsBatchWithoutBlockingLaterReports() {
        FakeGateway gateway = new FakeGateway();
        gateway.protocolFailure = true;
        BoundedDeliveryReportQueue queue = new BoundedDeliveryReportQueue(4);
        queue.offer("bad-report");
        DeliveryReportPump pump = new DeliveryReportPump(
                gateway,
                "adapter-1",
                queue
        );

        pump.run();
        gateway.protocolFailure = false;
        queue.offer("next-report");
        pump.run();

        assertThat(gateway.attempts).containsExactly(
                List.of("bad-report"),
                List.of("next-report")
        );
        assertThat(pump.pendingBatch()).isNull();
    }

    @Test
    void shutdownStopsOffersAndFlushesBufferedBatch() {
        FakeGateway gateway = new FakeGateway();
        BoundedDeliveryReportQueue queue = new BoundedDeliveryReportQueue(4);
        queue.offer("report-1");
        DeliveryReportPump pump = new DeliveryReportPump(
                gateway,
                "adapter-1",
                queue
        );

        pump.closeAndFlush();
        pump.closeAndFlush();

        assertThat(gateway.attempts).containsExactly(List.of("report-1"));
        assertThat(queue.offer("late"))
                .isEqualTo(BoundedDeliveryReportQueue.OfferStatus.CLOSED);
    }

    private static final class FakeGateway
            implements WorkerDeliveryGatewayClient {

        private final ArrayDeque<Boolean> outcomes = new ArrayDeque<>();
        private final List<List<String>> attempts = new ArrayList<>();
        private boolean protocolFailure;

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
            attempts.add(List.copyOf(encodedDeliveryReports));
            if (protocolFailure) {
                throw new WorkerDeliveryAdapterException(
                        WorkerDeliveryAdapterErrorCode.GATEWAY_PROTOCOL_ERROR,
                        "gateway.appendResults",
                        "bad response",
                        null
                );
            }
            if (!outcomes.isEmpty() && !outcomes.removeFirst()) {
                throw new WorkerDeliveryAdapterException(
                        WorkerDeliveryAdapterErrorCode.GATEWAY_UNAVAILABLE,
                        "gateway.appendResults",
                        "unavailable",
                        null
                );
            }
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> verifyWorkerRoute(
                String endpointManagerId,
                String workerId
        ) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    }
}
