package com.xa.mass.workerdelivery.adapter.dispatch;

import static com.xa.mass.workerdelivery.adapter.dispatch.WorkerCommandDelivery.CommandDeliveryAttempt.RETRY_LATER;
import static com.xa.mass.workerdelivery.adapter.dispatch.WorkerCommandDelivery.CommandDeliveryAttempt.STARTED;
import static com.xa.mass.workerdelivery.adapter.dispatch.WorkerCommandDelivery.CommandDeliveryAttempt.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.result.BoundedWorkerResultQueue;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkerCommandLoopTest {

    private static final String COMMAND_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";

    @Test
    void refillsOnlyWhenACompleteConsumeBatchFits() {
        FakeGateway gateway = new FakeGateway();
        gateway.batches.add(commands("worker-1", "worker-2"));
        RecordingDelivery delivery = new RecordingDelivery(RETRY_LATER);
        WorkerCommandLoop loop = loop(
                gateway,
                delivery,
                new BoundedWorkerResultQueue(10),
                2,
                3,
                1_000
        );

        loop.run();
        loop.run();

        assertThat(gateway.requestedLimits).containsExactly(2);
        assertThat(delivery.workerIds)
                .containsExactly(
                        "worker-1",
                        "worker-2",
                        "worker-1",
                        "worker-2"
                );
        assertThat(loop.queuedCommandCount()).isEqualTo(2);
    }

    @Test
    void consumeFailureDoesNotPreventForwardingQueuedCommands() {
        FakeGateway gateway = new FakeGateway();
        gateway.batches.add(commands("worker-1"));
        RecordingDelivery delivery = new RecordingDelivery(RETRY_LATER);
        WorkerCommandLoop loop = loop(
                gateway,
                delivery,
                new BoundedWorkerResultQueue(10),
                1,
                2,
                1_000
        );

        loop.run();
        gateway.consumeFailures = 1;
        delivery.attempt = STARTED;
        loop.run();

        assertThat(gateway.requestedLimits).containsExactly(1, 1);
        assertThat(delivery.workerIds)
                .containsExactly("worker-1", "worker-1");
        assertThat(loop.queuedCommandCount()).isZero();
    }

    @Test
    void rotatesEachQueuedCommandOnlyOncePerRound() {
        FakeGateway gateway = new FakeGateway();
        gateway.batches.add(commands("worker-1", "worker-2", "worker-3"));
        RecordingDelivery delivery = new RecordingDelivery(RETRY_LATER);
        WorkerCommandLoop loop = loop(
                gateway,
                delivery,
                new BoundedWorkerResultQueue(10),
                3,
                3,
                1_000
        );

        loop.run();

        assertThat(delivery.workerIds)
                .containsExactly("worker-1", "worker-2", "worker-3");
        assertThat(loop.queuedCommandCount()).isEqualTo(3);
    }

    @Test
    void removesStartedAndUnknownCommandsWithoutRetry() {
        FakeGateway gateway = new FakeGateway();
        gateway.batches.add(commands("worker-started", "worker-unknown"));
        WorkerCommandDelivery delivery = (workerId, command) ->
                "worker-started".equals(workerId) ? STARTED : UNKNOWN;
        WorkerCommandLoop loop = loop(
                gateway,
                delivery,
                new BoundedWorkerResultQueue(10),
                2,
                2,
                1_000
        );

        loop.run();

        assertThat(loop.queuedCommandCount()).isZero();
    }

    @Test
    void expiredOfflineCommandCreatesBestEffortAdapterRejection() {
        FakeGateway gateway = new FakeGateway();
        gateway.batches.add(Map.of(
                "worker-1",
                command(COMMAND_ID, "worker-1", 1_000)
        ));
        BoundedWorkerResultQueue results =
                new BoundedWorkerResultQueue(10);
        WorkerCommandLoop loop = loop(
                gateway,
                (workerId, command) -> RETRY_LATER,
                results,
                1,
                1,
                1_000
        );

        loop.run();

        assertThat(loop.queuedCommandCount()).isZero();
        assertThat(results.drainAll()).containsExactly(new SeedResult(
                COMMAND_ID,
                "context",
                "3001",
                null
        ));
    }

    @Test
    void fullResultQueueDoesNotKeepExpiredCommand() {
        FakeGateway gateway = new FakeGateway();
        gateway.batches.add(Map.of(
                "worker-1",
                command(COMMAND_ID, "worker-1", 1_000)
        ));
        BoundedWorkerResultQueue results =
                new BoundedWorkerResultQueue(1);
        results.offer(result("b5e9e10d-f78b-469e-93ab-864b49c189c1"));
        WorkerCommandLoop loop = loop(
                gateway,
                (workerId, command) -> RETRY_LATER,
                results,
                1,
                1,
                1_000
        );

        loop.run();

        assertThat(loop.queuedCommandCount()).isZero();
        assertThat(results.drainAll()).containsExactly(
                result("b5e9e10d-f78b-469e-93ab-864b49c189c1")
        );
    }

    private static WorkerCommandLoop loop(
            WorkerDeliveryGatewayClient gateway,
            WorkerCommandDelivery delivery,
            BoundedWorkerResultQueue resultQueue,
            int consumeLimit,
            int queueCapacity,
            long nowMillis
    ) {
        return new WorkerCommandLoop(
                gateway,
                delivery,
                resultQueue,
                "adapter-1",
                consumeLimit,
                queueCapacity,
                () -> nowMillis
        );
    }

    private static Map<String, WorkerCommandEnvelope> commands(
            String... workerIds
    ) {
        Map<String, WorkerCommandEnvelope> commands = new LinkedHashMap<>();
        for (int index = 0; index < workerIds.length; index++) {
            String workerId = workerIds[index];
            commands.put(
                    workerId,
                    command(
                            "00000000-0000-4000-8000-00000000000"
                                    + index,
                            workerId,
                            2_000
                    )
            );
        }
        return commands;
    }

    private static WorkerCommandEnvelope command(
            String commandId,
            String workerId,
            long deadline
    ) {
        return new WorkerCommandEnvelope(
                commandId,
                WorkerMessageType.TASK_ITEM,
                deadline,
                "{\"opaqueDeliveryItem\":\"item\","
                        + "\"opaqueResultContext\":\"context\","
                        + "\"workerId\":\"" + workerId + "\"}"
        );
    }

    private static SeedResult result(String commandId) {
        return new SeedResult(commandId, "context", "200", "null");
    }

    private static final class RecordingDelivery
            implements WorkerCommandDelivery {

        private final List<String> workerIds = new ArrayList<>();
        private CommandDeliveryAttempt attempt;

        private RecordingDelivery(CommandDeliveryAttempt attempt) {
            this.attempt = attempt;
        }

        @Override
        public CommandDeliveryAttempt deliver(
                String workerId,
                WorkerCommandEnvelope command
        ) {
            workerIds.add(workerId);
            return attempt;
        }
    }

    private static final class FakeGateway
            implements WorkerDeliveryGatewayClient {

        private final ConcurrentLinkedQueue<
                Map<String, WorkerCommandEnvelope>
                > batches = new ConcurrentLinkedQueue<>();
        private final List<Integer> requestedLimits = new ArrayList<>();
        private int consumeFailures;

        @Override
        public Map<String, WorkerCommandEnvelope> consumeWorkerCommands(
                String endpointManagerId,
                int limit
        ) {
            requestedLimits.add(limit);
            if (consumeFailures > 0) {
                consumeFailures--;
                throw new IllegalStateException("unavailable");
            }
            Map<String, WorkerCommandEnvelope> batch = batches.poll();
            return batch == null ? Map.of() : batch;
        }

        @Override
        public void appendResults(
                String endpointManagerId,
                List<SeedResult> results
        ) {
            throw new AssertionError("Command loop must not append directly");
        }
    }
}
