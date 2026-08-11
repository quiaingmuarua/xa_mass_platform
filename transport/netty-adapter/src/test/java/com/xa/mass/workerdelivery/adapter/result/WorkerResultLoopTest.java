package com.xa.mass.workerdelivery.adapter.result;

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

class WorkerResultLoopTest {

    @Test
    void oneRoundSubmitsAllCurrentlyBufferedResultsOnce() {
        FakeGateway gateway = new FakeGateway();
        BoundedWorkerResultQueue queue = new BoundedWorkerResultQueue(4);
        queue.offer("result-1");
        queue.offer("result-2");
        WorkerResultLoop loop = new WorkerResultLoop(
                gateway,
                "adapter-1",
                queue
        );

        loop.run();
        loop.run();

        assertThat(gateway.attempts).containsExactly(
                List.of("result-1", "result-2")
        );
        assertThat(queue.isEmpty()).isTrue();
        assertThat(loop.pendingBatch()).isNull();
    }

    @Test
    void failedBatchIsRetriedBeforeNewlyBufferedResults() {
        FakeGateway gateway = new FakeGateway();
        gateway.outcomes.add(false);
        gateway.outcomes.add(true);
        BoundedWorkerResultQueue queue = new BoundedWorkerResultQueue(4);
        queue.offer("result-1");
        WorkerResultLoop loop = new WorkerResultLoop(
                gateway,
                "adapter-1",
                queue
        );

        loop.run();
        queue.offer("result-2");
        loop.run();

        assertThat(gateway.attempts).containsExactly(
                List.of("result-1"),
                List.of("result-1")
        );
        assertThat(loop.pendingBatch()).isNull();
        assertThat(queue.isEmpty()).isFalse();

        loop.run();
        assertThat(gateway.attempts.get(2)).containsExactly("result-2");
    }

    @Test
    void protocolFailureDropsBatchWithoutBlockingLaterResults() {
        FakeGateway gateway = new FakeGateway();
        gateway.protocolFailure = true;
        BoundedWorkerResultQueue queue = new BoundedWorkerResultQueue(4);
        queue.offer("bad-result");
        WorkerResultLoop loop = new WorkerResultLoop(
                gateway,
                "adapter-1",
                queue
        );

        loop.run();
        gateway.protocolFailure = false;
        queue.offer("next-result");
        loop.run();

        assertThat(gateway.attempts).containsExactly(
                List.of("bad-result"),
                List.of("next-result")
        );
        assertThat(loop.pendingBatch()).isNull();
    }

    @Test
    void shutdownStopsOffersAndFlushesPendingThenBufferedBatch() {
        FakeGateway gateway = new FakeGateway();
        BoundedWorkerResultQueue queue = new BoundedWorkerResultQueue(4);
        queue.offer("result-1");
        WorkerResultLoop loop = new WorkerResultLoop(
                gateway,
                "adapter-1",
                queue
        );

        loop.closeAndFlush();
        loop.closeAndFlush();

        assertThat(gateway.attempts).containsExactly(List.of("result-1"));
        assertThat(queue.offer("late"))
                .isEqualTo(BoundedWorkerResultQueue.OfferStatus.CLOSED);
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
                List<String> encodedWorkerResults
        ) {
            attempts.add(List.copyOf(encodedWorkerResults));
            if (protocolFailure) {
                throw new WorkerDeliveryAdapterException(
                        WorkerDeliveryAdapterErrorCode
                                .GATEWAY_PROTOCOL_ERROR,
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
        public java.util.concurrent.CompletionStage<Void>
        verifyWorkerRoute(String endpointManagerId, String workerId) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    null
            );
        }
    }
}
