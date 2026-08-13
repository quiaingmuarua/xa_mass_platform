package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryCommandProcess.DeliveryAttempt.RETRY_LATER;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryCommandProcess.DeliveryAttempt.STARTED;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryCommandProcess.DeliveryAttempt.UNKNOWN;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess.ReportIngressStatus.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess.ReportIngressStatus.FULL;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient.CommandSource;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class DeliveryCommandProcessTest {

    private static final WorkerDeliveryCodec CODEC =
            new WorkerDeliveryCodec();

    @Test
    void softCapacityAllowsOneSourceBatchOfRedundancy() {
        FakeSource source = new FakeSource();
        source.batches.add(commands("worker-1", "worker-2"));
        source.batches.add(commands("worker-3", "worker-4"));
        source.batches.add(commands("worker-5"));
        RecordingTarget target = new RecordingTarget(workerId -> RETRY_LATER);
        DeliveryCommandProcess process = process(
                source,
                target,
                reports(ACCEPTED),
                2,
                3
        );

        process.round();
        process.round();
        process.round();

        assertThat(source.requestedLimits).containsExactly(2, 2);
        assertThat(target.workerIds).hasSize(8);
    }

    @Test
    void sourceFailureDoesNotPreventQueuedCommandDelivery() {
        FakeSource source = new FakeSource();
        source.batches.add(commands("worker-1"));
        RecordingTarget target = new RecordingTarget(workerId -> RETRY_LATER);
        DeliveryCommandProcess process = process(
                source,
                target,
                reports(ACCEPTED),
                1,
                2
        );

        process.round();
        source.failures = 1;
        target.attempt = workerId -> STARTED;
        process.round();

        assertThat(source.requestedLimits).containsExactly(1, 1);
        assertThat(target.workerIds).containsExactly("worker-1", "worker-1");
    }

    @Test
    void eachObservedCommandRunsOnceAndOnlyRetryLaterReturns() {
        FakeSource source = new FakeSource();
        source.batches.add(commands(
                "worker-started",
                "worker-unknown",
                "worker-retry"
        ));
        RecordingTarget target = new RecordingTarget(workerId -> switch (
                workerId
        ) {
            case "worker-started" -> STARTED;
            case "worker-unknown" -> UNKNOWN;
            default -> RETRY_LATER;
        });
        DeliveryCommandProcess process = process(
                source,
                target,
                reports(ACCEPTED),
                3,
                3
        );

        process.round();
        process.round();

        assertThat(target.workerIds).containsExactly(
                "worker-started",
                "worker-unknown",
                "worker-retry",
                "worker-retry"
        );
    }

    @Test
    void expiredCommandCreatesBestEffortAdapterResult() {
        FakeSource source = new FakeSource();
        DeliveryCommand expired = command(1_000, "expired-context");
        source.batches.add(Map.of("worker-1", expired));
        RecordingReports reports = reports(ACCEPTED);
        DeliveryCommandProcess process = process(
                source,
                new RecordingTarget(workerId -> RETRY_LATER),
                reports,
                1,
                1
        );

        process.round();

        assertThat(reports.batches).hasSize(1);
        assertThat(CODEC.decodeDeliveryReport(
                reports.batches.get(0).get(0)
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
    void rejectedExpiredResultDoesNotRetainTheCommand() {
        FakeSource source = new FakeSource();
        source.batches.add(Map.of(
                "worker-1",
                command(1_000, "expired-context")
        ));
        RecordingTarget target = new RecordingTarget(workerId -> STARTED);
        DeliveryCommandProcess process = process(
                source,
                target,
                reports(FULL),
                1,
                1
        );

        process.round();
        process.round();

        assertThat(target.workerIds).isEmpty();
    }

    @Test
    void stopPreventsFurtherSourceAndTargetWork() {
        FakeSource source = new FakeSource();
        source.batches.add(commands("worker-1"));
        RecordingTarget target = new RecordingTarget(workerId -> RETRY_LATER);
        DeliveryCommandProcess process = process(
                source,
                target,
                reports(ACCEPTED),
                1,
                2
        );
        process.round();

        process.stopRounds();
        process.round();
        process.finishCloseAfterSchedulerStop();
        process.finishCloseAfterSchedulerStop();

        assertThat(source.requestedLimits).containsExactly(1);
        assertThat(target.workerIds).containsExactly("worker-1");
    }

    private static DeliveryCommandProcess process(
            CommandSource source,
            DeliveryCommandProcess.Target target,
            DeliveryReportProcess.Acceptor reports,
            int consumeLimit,
            int capacity
    ) {
        return new DeliveryCommandProcess(
                source,
                target,
                reports,
                CODEC,
                "adapter-1",
                consumeLimit,
                capacity,
                () -> 1_000
        );
    }

    private static RecordingReports reports(
            DeliveryReportProcess.ReportIngressStatus status
    ) {
        return new RecordingReports(status);
    }

    private static Map<String, DeliveryCommand> commands(String... workerIds) {
        Map<String, DeliveryCommand> commands = new LinkedHashMap<>();
        for (String workerId : workerIds) {
            commands.put(workerId, command(2_000, workerId + "-context"));
        }
        return commands;
    }

    private static DeliveryCommand command(long deadline, String forward) {
        return DeliveryCommand.create(
                TASK,
                WORKER,
                "test.observe",
                deadline,
                "{}",
                forward
        );
    }

    private static final class FakeSource implements CommandSource {

        private final ArrayDeque<Map<String, DeliveryCommand>> batches =
                new ArrayDeque<>();
        private final List<Integer> requestedLimits = new ArrayList<>();
        private int failures;

        @Override
        public Map<String, DeliveryCommand> consume(
                String endpointManagerId,
                int limit
        ) {
            requestedLimits.add(limit);
            if (failures > 0) {
                failures--;
                throw new IllegalStateException("unavailable");
            }
            Map<String, DeliveryCommand> batch = batches.pollFirst();
            return batch == null ? Map.of() : batch;
        }
    }

    private static final class RecordingTarget
            implements DeliveryCommandProcess.Target {

        private final List<String> workerIds = new ArrayList<>();
        private Function<String, DeliveryCommandProcess.DeliveryAttempt>
                attempt;

        private RecordingTarget(
                Function<String, DeliveryCommandProcess.DeliveryAttempt>
                        attempt
        ) {
            this.attempt = attempt;
        }

        @Override
        public DeliveryCommandProcess.DeliveryAttempt deliver(
                String workerId,
                DeliveryCommand command
        ) {
            workerIds.add(workerId);
            return attempt.apply(workerId);
        }
    }

    private static final class RecordingReports
            implements DeliveryReportProcess.Acceptor {

        private final List<List<String>> batches = new ArrayList<>();
        private final DeliveryReportProcess.ReportIngressStatus status;

        private RecordingReports(
                DeliveryReportProcess.ReportIngressStatus status
        ) {
            this.status = status;
        }

        @Override
        public DeliveryReportProcess.ReportIngressStatus ingress(
                List<String> encodedReports
        ) {
            batches.add(List.copyOf(encodedReports));
            return status;
        }
    }
}
