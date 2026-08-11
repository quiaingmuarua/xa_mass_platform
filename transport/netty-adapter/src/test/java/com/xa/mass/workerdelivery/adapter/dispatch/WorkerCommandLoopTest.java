package com.xa.mass.workerdelivery.adapter.dispatch;

import static com.xa.mass.workerdelivery.adapter.dispatch.WorkerCommandDelivery.CommandDeliveryAttempt.RETRY_LATER;
import static com.xa.mass.workerdelivery.adapter.dispatch.WorkerCommandDelivery.CommandDeliveryAttempt.STARTED;
import static com.xa.mass.workerdelivery.adapter.dispatch.WorkerCommandDelivery.CommandDeliveryAttempt.UNKNOWN;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.result.BoundedWorkerResultQueue;
import com.xa.mass.workerdelivery.adapter.result.WorkerResultLoop;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkerCommandLoopTest {

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
        DeliveryCommand expired = command("worker-1", 1_000);
        gateway.batches.add(Map.of(
                "worker-1",
                expired
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
        new WorkerResultLoop(gateway, "adapter-1", results).run();

        assertThat(loop.queuedCommandCount()).isZero();
        assertThat(gateway.appendedResults).hasSize(1);
        assertThat(new WorkerDeliveryCodec().decodeDeliveryReport(
                gateway.appendedResults.get(0).get(0)
        )).isEqualTo(DeliveryReport.fromCommand(
                expired,
                ADAPTER,
                "adapter-1",
                Integer.toString(
                        WorkerDeliveryAdapterErrorCode.COMMAND_EXPIRED.code()
                ),
                "null"
        ));
    }

    @Test
    void fullResultQueueDoesNotKeepExpiredCommand() {
        FakeGateway gateway = new FakeGateway();
        gateway.batches.add(Map.of(
                "worker-1",
                command("worker-1", 1_000)
        ));
        BoundedWorkerResultQueue results =
                new BoundedWorkerResultQueue(1);
        DeliveryReport existing =
                result("existing-context");
        results.offer(new WorkerDeliveryCodec().encodeDeliveryReport(existing));
        WorkerCommandLoop loop = loop(
                gateway,
                (workerId, command) -> RETRY_LATER,
                results,
                1,
                1,
                1_000
        );

        loop.run();
        new WorkerResultLoop(gateway, "adapter-1", results).run();

        assertThat(loop.queuedCommandCount()).isZero();
        assertThat(gateway.appendedResults)
                .containsExactly(List.of(
                        new WorkerDeliveryCodec()
                                .encodeDeliveryReport(existing)
                ));
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

    private static Map<String, DeliveryCommand> commands(
            String... workerIds
    ) {
        Map<String, DeliveryCommand> commands = new LinkedHashMap<>();
        for (int index = 0; index < workerIds.length; index++) {
            String workerId = workerIds[index];
            commands.put(
                    workerId,
                    command(
                            workerId,
                            2_000
                    )
            );
        }
        return commands;
    }

    private static DeliveryCommand command(
            String workerId,
            long deadline
    ) {
        return DeliveryCommand.create(
                TASK,
                WORKER,
                "test.observe",
                deadline,
                "{}",
                "context"
        );
    }

    private static DeliveryReport result(String forward) {
        return DeliveryReport.create(
                WORKER,
                "worker-1",
                TASK,
                "test.observe",
                "200",
                "null",
                forward
        );
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
                DeliveryCommand command
        ) {
            workerIds.add(workerId);
            return attempt;
        }
    }

    private static final class FakeGateway
            implements WorkerDeliveryGatewayClient {

        private final ConcurrentLinkedQueue<
                Map<String, DeliveryCommand>
                > batches = new ConcurrentLinkedQueue<>();
        private final List<Integer> requestedLimits = new ArrayList<>();
        private final List<List<String>> appendedResults =
                new ArrayList<>();
        private int consumeFailures;

        @Override
        public Map<String, DeliveryCommand> consumeWorkerCommands(
                String endpointManagerId,
                int limit
        ) {
            requestedLimits.add(limit);
            if (consumeFailures > 0) {
                consumeFailures--;
                throw new IllegalStateException("unavailable");
            }
            Map<String, DeliveryCommand> batch = batches.poll();
            return batch == null ? Map.of() : batch;
        }

        @Override
        public void appendResults(
                String endpointManagerId,
                List<String> results
        ) {
            appendedResults.add(List.copyOf(results));
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
