package com.xa.mass.workerdelivery.adapter.internal;

import static com.xa.mass.workerdelivery.adapter.internal.DeliveryCommandTarget.DeliveryAttempt.RETRY_LATER;
import static com.xa.mass.workerdelivery.adapter.internal.DeliveryCommandTarget.DeliveryAttempt.STARTED;
import static com.xa.mass.workerdelivery.adapter.internal.DeliveryCommandTarget.DeliveryAttempt.UNKNOWN;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;

class DeliveryCommandPumpTest {

    @Test
    void refillsOnlyWhenACompleteConsumeBatchFits() {
        FakeGateway gateway = new FakeGateway();
        gateway.batches.add(commands("worker-1", "worker-2"));
        RecordingTarget target = new RecordingTarget(RETRY_LATER);
        DeliveryCommandPump pump = pump(
                gateway,
                target,
                new BoundedDeliveryReportQueue(10),
                2,
                3,
                1_000
        );

        pump.run();
        pump.run();

        assertThat(gateway.requestedLimits).containsExactly(2);
        assertThat(target.workerIds)
                .containsExactly(
                        "worker-1",
                        "worker-2",
                        "worker-1",
                        "worker-2"
                );
        assertThat(pump.queuedCommandCount()).isEqualTo(2);
    }

    @Test
    void consumeFailureDoesNotPreventForwardingQueuedCommands() {
        FakeGateway gateway = new FakeGateway();
        gateway.batches.add(commands("worker-1"));
        RecordingTarget target = new RecordingTarget(RETRY_LATER);
        DeliveryCommandPump pump = pump(
                gateway,
                target,
                new BoundedDeliveryReportQueue(10),
                1,
                2,
                1_000
        );

        pump.run();
        gateway.consumeFailures = 1;
        target.attempt = STARTED;
        pump.run();

        assertThat(gateway.requestedLimits).containsExactly(1, 1);
        assertThat(target.workerIds).containsExactly("worker-1", "worker-1");
        assertThat(pump.queuedCommandCount()).isZero();
    }

    @Test
    void rotatesEachQueuedCommandOnlyOncePerRound() {
        FakeGateway gateway = new FakeGateway();
        gateway.batches.add(commands("worker-1", "worker-2", "worker-3"));
        RecordingTarget target = new RecordingTarget(RETRY_LATER);
        DeliveryCommandPump pump = pump(
                gateway,
                target,
                new BoundedDeliveryReportQueue(10),
                3,
                3,
                1_000
        );

        pump.run();

        assertThat(target.workerIds)
                .containsExactly("worker-1", "worker-2", "worker-3");
        assertThat(pump.queuedCommandCount()).isEqualTo(3);
    }

    @Test
    void removesStartedAndUnknownCommandsWithoutRetry() {
        FakeGateway gateway = new FakeGateway();
        gateway.batches.add(commands("worker-started", "worker-unknown"));
        DeliveryCommandTarget target = (workerId, command) ->
                "worker-started".equals(workerId) ? STARTED : UNKNOWN;
        DeliveryCommandPump pump = pump(
                gateway,
                target,
                new BoundedDeliveryReportQueue(10),
                2,
                2,
                1_000
        );

        pump.run();

        assertThat(pump.queuedCommandCount()).isZero();
    }

    @Test
    void expiredOfflineCommandCreatesBestEffortAdapterRejection() {
        FakeGateway gateway = new FakeGateway();
        DeliveryCommand expired = command(1_000);
        gateway.batches.add(Map.of("worker-1", expired));
        BoundedDeliveryReportQueue reports =
                new BoundedDeliveryReportQueue(10);
        DeliveryCommandPump pump = pump(
                gateway,
                (workerId, command) -> RETRY_LATER,
                reports,
                1,
                1,
                1_000
        );

        pump.run();
        new DeliveryReportPump(gateway, "adapter-1", reports).run();

        assertThat(pump.queuedCommandCount()).isZero();
        assertThat(gateway.appendedReports).hasSize(1);
        assertThat(new WorkerDeliveryCodec().decodeDeliveryReport(
                gateway.appendedReports.get(0).get(0)
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
    void fullReportQueueDoesNotKeepExpiredCommand() {
        FakeGateway gateway = new FakeGateway();
        gateway.batches.add(Map.of("worker-1", command(1_000)));
        BoundedDeliveryReportQueue reports =
                new BoundedDeliveryReportQueue(1);
        DeliveryReport existing = report("existing-context");
        reports.offer(new WorkerDeliveryCodec().encodeDeliveryReport(existing));
        DeliveryCommandPump pump = pump(
                gateway,
                (workerId, command) -> RETRY_LATER,
                reports,
                1,
                1,
                1_000
        );

        pump.run();
        new DeliveryReportPump(gateway, "adapter-1", reports).run();

        assertThat(pump.queuedCommandCount()).isZero();
        assertThat(gateway.appendedReports)
                .containsExactly(List.of(
                        new WorkerDeliveryCodec()
                                .encodeDeliveryReport(existing)
                ));
    }

    private static DeliveryCommandPump pump(
            WorkerDeliveryGatewayClient gateway,
            DeliveryCommandTarget target,
            BoundedDeliveryReportQueue reportQueue,
            int consumeLimit,
            int queueCapacity,
            long nowMillis
    ) {
        return new DeliveryCommandPump(
                gateway,
                target,
                reportQueue,
                "adapter-1",
                consumeLimit,
                queueCapacity,
                () -> nowMillis
        );
    }

    private static Map<String, DeliveryCommand> commands(String... workerIds) {
        Map<String, DeliveryCommand> commands = new LinkedHashMap<>();
        for (String workerId : workerIds) {
            commands.put(workerId, command(2_000));
        }
        return commands;
    }

    private static DeliveryCommand command(long deadline) {
        return DeliveryCommand.create(
                TASK,
                WORKER,
                "test.observe",
                deadline,
                "{}",
                "context"
        );
    }

    private static DeliveryReport report(String forward) {
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

    private static final class RecordingTarget
            implements DeliveryCommandTarget {

        private final List<String> workerIds = new ArrayList<>();
        private DeliveryAttempt attempt;

        private RecordingTarget(DeliveryAttempt attempt) {
            this.attempt = attempt;
        }

        @Override
        public DeliveryAttempt deliver(
                String workerId,
                DeliveryCommand command
        ) {
            workerIds.add(workerId);
            return attempt;
        }
    }

    private static final class FakeGateway
            implements WorkerDeliveryGatewayClient {

        private final ConcurrentLinkedQueue<Map<String, DeliveryCommand>>
                batches = new ConcurrentLinkedQueue<>();
        private final List<Integer> requestedLimits = new ArrayList<>();
        private final List<List<String>> appendedReports = new ArrayList<>();
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
                List<String> encodedDeliveryReports
        ) {
            appendedReports.add(List.copyOf(encodedDeliveryReports));
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
