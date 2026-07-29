package com.xa.mass.workerdelivery.adapter.result;

import static com.xa.mass.workerdelivery.adapter.result.BoundedWorkerResultQueue.OfferStatus.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.result.BoundedWorkerResultQueue.OfferStatus.CLOSED;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerResultLoopTest {

    @Test
    void emptyQueueDoesNotCallGateway() {
        FakeGateway gateway = new FakeGateway();
        WorkerResultLoop loop = new WorkerResultLoop(
                gateway,
                "adapter-1",
                new BoundedWorkerResultQueue(10)
        );

        loop.run();

        assertThat(gateway.appendedResults).isEmpty();
    }

    @Test
    void oneTickDrainsCurrentQueueIntoOneRequest() {
        FakeGateway gateway = new FakeGateway();
        BoundedWorkerResultQueue queue =
                new BoundedWorkerResultQueue(10);
        queue.offer(result(1));
        queue.offer(result(2));
        WorkerResultLoop loop = new WorkerResultLoop(
                gateway,
                "adapter-1",
                queue
        );

        loop.run();

        assertThat(gateway.appendedResults)
                .containsExactly(List.of(result(1), result(2)));
        assertThat(queue.isEmpty()).isTrue();
    }

    @Test
    void pendingBatchHasPriorityAndEachTickMakesAtMostOneRequest() {
        FakeGateway gateway = new FakeGateway();
        gateway.failures = 1;
        BoundedWorkerResultQueue queue =
                new BoundedWorkerResultQueue(10);
        queue.offer(result(1));
        WorkerResultLoop loop = new WorkerResultLoop(
                gateway,
                "adapter-1",
                queue
        );

        loop.run();
        queue.offer(result(2));
        loop.run();

        assertThat(gateway.attemptedResults)
                .containsExactly(
                        List.of(result(1)),
                        List.of(result(1))
                );
        assertThat(gateway.appendedResults)
                .containsExactly(List.of(result(1)));
        assertThat(queue.isEmpty()).isFalse();

        loop.run();

        assertThat(gateway.appendedResults)
                .containsExactly(
                        List.of(result(1)),
                        List.of(result(2))
                );
    }

    @Test
    void closeRetriesPendingThenSubmitsCurrentQueueOnce() {
        FakeGateway gateway = new FakeGateway();
        gateway.failures = 1;
        BoundedWorkerResultQueue queue =
                new BoundedWorkerResultQueue(10);
        queue.offer(result(1));
        WorkerResultLoop loop = new WorkerResultLoop(
                gateway,
                "adapter-1",
                queue
        );
        loop.run();
        queue.offer(result(2));

        loop.closeAndFlush();

        assertThat(gateway.attemptedResults)
                .containsExactly(
                        List.of(result(1)),
                        List.of(result(1)),
                        List.of(result(2))
                );
        assertThat(gateway.appendedResults)
                .containsExactly(
                        List.of(result(1)),
                        List.of(result(2))
                );
        assertThat(queue.offer(result(3))).isEqualTo(CLOSED);
    }

    @Test
    void closeStopsAfterOneFailedPendingRetry() {
        FakeGateway gateway = new FakeGateway();
        gateway.failures = 2;
        BoundedWorkerResultQueue queue =
                new BoundedWorkerResultQueue(10);
        queue.offer(result(1));
        WorkerResultLoop loop = new WorkerResultLoop(
                gateway,
                "adapter-1",
                queue
        );
        loop.run();
        assertThat(queue.offer(result(2))).isEqualTo(ACCEPTED);

        loop.closeAndFlush();

        assertThat(gateway.attemptedResults)
                .containsExactly(
                        List.of(result(1)),
                        List.of(result(1))
                );
        assertThat(gateway.appendedResults).isEmpty();
    }

    private static SeedResult result(int index) {
        return new SeedResult(
                "00000000-0000-4000-8000-00000000000" + index,
                "context-" + index,
                "200",
                "null"
        );
    }

    private static final class FakeGateway
            implements WorkerDeliveryGatewayClient {

        private final List<List<SeedResult>> attemptedResults =
                new ArrayList<>();
        private final List<List<SeedResult>> appendedResults =
                new ArrayList<>();
        private int failures;

        @Override
        public Map<String, WorkerCommandEnvelope> consumeWorkerCommands(
                String endpointManagerId,
                int limit
        ) {
            throw new AssertionError("Result loop must not consume commands");
        }

        @Override
        public void appendResults(
                String endpointManagerId,
                List<SeedResult> results
        ) {
            List<SeedResult> copy = List.copyOf(results);
            attemptedResults.add(copy);
            if (failures > 0) {
                failures--;
                throw new IllegalStateException("unavailable");
            }
            appendedResults.add(copy);
        }
    }
}
