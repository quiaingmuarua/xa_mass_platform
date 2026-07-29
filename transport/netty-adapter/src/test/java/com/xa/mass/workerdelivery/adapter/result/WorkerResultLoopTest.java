package com.xa.mass.workerdelivery.adapter.result;

import static com.xa.mass.workerdelivery.adapter.result.BoundedWorkerResultQueue.OfferStatus.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.result.BoundedWorkerResultQueue.OfferStatus.CLOSED;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultSource.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultSource.WORKER;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultSource;
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

        assertThat(gateway.attempts).isEmpty();
    }

    @Test
    void oneTickSubmitsAtMostOneBatchForEachSource() {
        FakeGateway gateway = new FakeGateway();
        BoundedWorkerResultQueue queue =
                new BoundedWorkerResultQueue(10);
        queue.offer(WORKER, "worker-result-1");
        queue.offer(ADAPTER, "adapter-result-1");
        queue.offer(WORKER, "worker-result-2");
        WorkerResultLoop loop = new WorkerResultLoop(
                gateway,
                "adapter-1",
                queue
        );

        loop.run();

        assertThat(gateway.attempts)
                .containsExactly(
                        new Attempt(
                                WORKER,
                                List.of(
                                        "worker-result-1",
                                        "worker-result-2"
                                )
                        ),
                        new Attempt(
                                ADAPTER,
                                List.of("adapter-result-1")
                        )
                );
        assertThat(queue.isEmpty()).isTrue();
    }

    @Test
    void failedBatchRetriesBeforeNewResultsOfTheSameSource() {
        FakeGateway gateway = new FakeGateway();
        gateway.unavailableFailures = 1;
        BoundedWorkerResultQueue queue =
                new BoundedWorkerResultQueue(10);
        queue.offer(WORKER, "worker-result-1");
        WorkerResultLoop loop = new WorkerResultLoop(
                gateway,
                "adapter-1",
                queue
        );

        loop.run();
        queue.offer(WORKER, "worker-result-2");
        loop.run();

        assertThat(gateway.attempts)
                .containsExactly(
                        new Attempt(
                                WORKER,
                                List.of("worker-result-1")
                        ),
                        new Attempt(
                                WORKER,
                                List.of("worker-result-1")
                        )
                );
        assertThat(loop.pendingBatch(WORKER)).isNull();
        assertThat(queue.isEmpty()).isFalse();

        loop.run();

        assertThat(gateway.attempts.get(2))
                .isEqualTo(new Attempt(
                        WORKER,
                        List.of("worker-result-2")
                ));
    }

    @Test
    void protocolFailureDropsOnlyThatSourceBatch() {
        FakeGateway gateway = new FakeGateway();
        gateway.protocolFailures = 1;
        BoundedWorkerResultQueue queue =
                new BoundedWorkerResultQueue(10);
        queue.offer(WORKER, "invalid-worker-batch");
        queue.offer(ADAPTER, "adapter-result");
        WorkerResultLoop loop = new WorkerResultLoop(
                gateway,
                "adapter-1",
                queue
        );

        loop.run();
        queue.offer(WORKER, "next-worker-result");
        loop.run();

        assertThat(gateway.attempts)
                .containsExactly(
                        new Attempt(
                                WORKER,
                                List.of("invalid-worker-batch")
                        ),
                        new Attempt(
                                ADAPTER,
                                List.of("adapter-result")
                        ),
                        new Attempt(
                                WORKER,
                                List.of("next-worker-result")
                        )
                );
        assertThat(loop.pendingBatch(WORKER)).isNull();
    }

    @Test
    void closeRetriesPendingThenSubmitsCurrentQueueOnce() {
        FakeGateway gateway = new FakeGateway();
        gateway.unavailableFailures = 1;
        BoundedWorkerResultQueue queue =
                new BoundedWorkerResultQueue(10);
        queue.offer(WORKER, "worker-result-1");
        WorkerResultLoop loop = new WorkerResultLoop(
                gateway,
                "adapter-1",
                queue
        );
        loop.run();
        queue.offer(WORKER, "worker-result-2");

        loop.closeAndFlush();

        assertThat(gateway.attempts)
                .containsExactly(
                        new Attempt(
                                WORKER,
                                List.of("worker-result-1")
                        ),
                        new Attempt(
                                WORKER,
                                List.of("worker-result-1")
                        ),
                        new Attempt(
                                WORKER,
                                List.of("worker-result-2")
                        )
                );
        assertThat(queue.offer(WORKER, "worker-result-3"))
                .isEqualTo(CLOSED);
    }

    @Test
    void closeStopsAfterOneFailedPendingRetry() {
        FakeGateway gateway = new FakeGateway();
        gateway.unavailableFailures = 2;
        BoundedWorkerResultQueue queue =
                new BoundedWorkerResultQueue(10);
        queue.offer(WORKER, "worker-result-1");
        WorkerResultLoop loop = new WorkerResultLoop(
                gateway,
                "adapter-1",
                queue
        );
        loop.run();
        assertThat(queue.offer(WORKER, "worker-result-2"))
                .isEqualTo(ACCEPTED);

        loop.closeAndFlush();

        assertThat(gateway.attempts)
                .containsExactly(
                        new Attempt(
                                WORKER,
                                List.of("worker-result-1")
                        ),
                        new Attempt(
                                WORKER,
                                List.of("worker-result-1")
                        )
                );
    }

    private static final class FakeGateway
            implements WorkerDeliveryGatewayClient {

        private final List<Attempt> attempts = new ArrayList<>();
        private int unavailableFailures;
        private int protocolFailures;

        @Override
        public Map<String, WorkerCommandEnvelope> consumeWorkerCommands(
                String endpointManagerId,
                int limit
        ) {
            throw new AssertionError(
                    "Result loop must not consume commands"
            );
        }

        @Override
        public void appendResults(
                String endpointManagerId,
                SeedResultSource source,
                List<String> results
        ) {
            attempts.add(new Attempt(source, List.copyOf(results)));
            if (protocolFailures > 0) {
                protocolFailures--;
                throw new WorkerDeliveryAdapterException(
                        WorkerDeliveryAdapterErrorCode
                                .GATEWAY_PROTOCOL_ERROR,
                        "gateway.appendResults",
                        "invalid response",
                        null
                );
            }
            if (unavailableFailures > 0) {
                unavailableFailures--;
                throw new WorkerDeliveryAdapterException(
                        WorkerDeliveryAdapterErrorCode
                                .GATEWAY_UNAVAILABLE,
                        "gateway.appendResults",
                        "unavailable",
                        null
                );
            }
        }
    }

    private static final class Attempt {

        private final SeedResultSource source;
        private final List<String> results;

        private Attempt(
                SeedResultSource source,
                List<String> results
        ) {
            this.source = source;
            this.results = results;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Attempt)) {
                return false;
            }
            Attempt that = (Attempt) other;
            return source == that.source && results.equals(that.results);
        }

        @Override
        public int hashCode() {
            return 31 * source.hashCode() + results.hashCode();
        }

        @Override
        public String toString() {
            return source + ":" + results;
        }
    }
}
