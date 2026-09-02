package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism.DeliveryAttempt.RETRY_LATER;
import static com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism.DeliveryAttempt.STARTED;
import static com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism.DeliveryAttempt.UNKNOWN;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.KERNEL;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.SYSTEM;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeliveryCommandProcessTest {

    private static final WorkerDeliveryCodec CODEC = new WorkerDeliveryCodec();

    @Test
    void returnsOnlyIndexesWhoseCurrentRouteRequestsRetry() {
        WorkerConnectionMechanism connection = mock(
                WorkerConnectionMechanism.class
        );
        when(connection.deliver("worker-retry", taskCommand(2_000)))
                .thenReturn(RETRY_LATER);
        when(connection.deliver("worker-started", taskCommand(2_000)))
                .thenReturn(STARTED);
        when(connection.deliver("worker-unknown", taskCommand(2_000)))
                .thenReturn(UNKNOWN);
        DeliveryCommandProcess process = process(
                connection,
                acceptingReportDispatcher()
        );
        DeliveryCommandItem retry = new DeliveryCommandItem(
                "worker-retry",
                taskCommand(2_000)
        );
        DeliveryCommandItem started = new DeliveryCommandItem(
                "worker-started",
                taskCommand(2_000)
        );
        DeliveryCommandItem unknown = new DeliveryCommandItem(
                "worker-unknown",
                taskCommand(2_000)
        );

        BatchProcessResult result = process.process(List.of(
                retry,
                started,
                unknown
        ));

        assertThat(result.errorCode()).isEqualTo(
                WorkerDeliveryAdapterErrorCode
                        .WORKER_DELIVERY_RETRY_LATER
        );
        assertThat(result.requeueIndexes()).containsExactly(0);
        verify(connection).deliver("worker-retry", retry.command());
        verify(connection).deliver("worker-started", started.command());
        verify(connection).deliver("worker-unknown", unknown.command());
    }

    @Test
    void expiredTaskProducesItsResultAndKernelEvidenceAsOneIngressBatch() {
        WorkerConnectionMechanism connection = mock(
                WorkerConnectionMechanism.class
        );
        List<List<String>> offered = new ArrayList<>();
        DeliveryCommandProcess process = process(
                connection,
                reportDispatcher(offered)
        );
        DeliveryCommand expired = taskCommand(1_000);

        assertThat(process.process(List.of(new DeliveryCommandItem(
                "worker-1",
                expired
        )))).isEqualTo(BatchProcessResult.completed());

        assertThat(offered).singleElement().satisfies(batch -> {
            assertThat(batch).hasSize(2);
            List<DeliveryReport> decoded = batch.stream()
                    .map(CODEC::decodeDeliveryReport)
                    .toList();
            assertThat(decoded).anySatisfy(report -> assertThat(report)
                    .isEqualTo(DeliveryReport.fromCommand(
                            expired,
                            ADAPTER,
                            "adapter-1",
                            Integer.toString(
                                    WorkerDeliveryAdapterErrorCode
                                            .COMMAND_EXPIRED.code()
                            ),
                            "null"
                    )));
            assertThat(decoded).anySatisfy(report -> {
                assertThat(report.dst()).isEqualTo(KERNEL);
                assertThat(report.messageType()).isEqualTo(
                        "platform.adapter.worker-delivery.expired"
                );
                assertThat(report.forward()).isEqualTo(
                        "worker-serviceability-evidence:v1"
                );
                assertThat(Jsons.parseObject(report.payload()))
                        .containsExactlyInAnyOrderEntriesOf(Map.of(
                                "workerId", "worker-1",
                                "observedAtMillis", 1_000L
                        ));
            });
        });
    }

    @Test
    void adapterCommandUsesDispatcherAndOffersItsResult() {
        WorkerConnectionMechanism connection = mock(
                WorkerConnectionMechanism.class
        );
        AdapterEventDispatcher dispatcher = mock(AdapterEventDispatcher.class);
        DeliveryCommand command = DeliveryCommand.create(
                SYSTEM,
                ADAPTER,
                "platform.adapter.probe",
                2_000,
                "null",
                "direct-call:v1:test"
        );
        DeliveryReport report = DeliveryReport.fromCommand(
                command,
                ADAPTER,
                "adapter-1",
                "200",
                "null"
        );
        when(dispatcher.dispatch(command)).thenReturn(report);
        List<List<String>> offered = new ArrayList<>();
        DeliveryCommandProcess process = new DeliveryCommandProcess(
                connection,
                dispatcher,
                reportDispatcher(offered),
                CODEC,
                "adapter-1",
                () -> 1_000
        );

        assertThat(process.process(List.of(new DeliveryCommandItem(
                "opaque-entry",
                command
        )))).isEqualTo(BatchProcessResult.completed());

        assertThat(offered).singleElement().satisfies(batch ->
                assertThat(batch).singleElement().satisfies(encoded ->
                        assertThat(CODEC.decodeDeliveryReport(encoded))
                                .isEqualTo(report)
                )
        );
    }

    @Test
    void unexpectedPartialDeliveryFailureEscapesWithoutBatchReplayDecision() {
        WorkerConnectionMechanism connection = mock(
                WorkerConnectionMechanism.class
        );
        DeliveryCommand command = taskCommand(2_000);
        doThrow(new IllegalStateException("write failed"))
                .when(connection)
                .deliver("worker-1", command);
        DeliveryCommandProcess process = process(
                connection,
                acceptingReportDispatcher()
        );

        assertThatThrownBy(() -> process.process(List.of(
                new DeliveryCommandItem("worker-1", command)
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessage("write failed");
    }

    private static DeliveryCommandProcess process(
            WorkerConnectionMechanism connection,
            BatchDispatcher<String> reportDispatcher
    ) {
        return new DeliveryCommandProcess(
                connection,
                mock(AdapterEventDispatcher.class),
                reportDispatcher,
                CODEC,
                "adapter-1",
                () -> 1_000
        );
    }

    @SuppressWarnings("unchecked")
    private static BatchDispatcher<String> acceptingReportDispatcher() {
        BatchDispatcher<String> dispatcher = mock(BatchDispatcher.class);
        when(dispatcher.tryDispatch(anyList())).thenReturn(
                BatchDispatcher.DispatchStatus.ACCEPTED
        );
        return dispatcher;
    }

    private static BatchDispatcher<String> reportDispatcher(
            List<List<String>> offered
    ) {
        BatchDispatcher<String> dispatcher = acceptingReportDispatcher();
        doAnswer(invocation -> {
            List<String> batch = invocation.getArgument(0);
            offered.add(List.copyOf(batch));
            return BatchDispatcher.DispatchStatus.ACCEPTED;
        }).when(dispatcher).tryDispatch(anyList());
        return dispatcher;
    }

    private static DeliveryCommand taskCommand(long deadline) {
        return DeliveryCommand.create(
                TASK,
                WORKER,
                "test.observe",
                deadline,
                "{}",
                "task-context"
        );
    }
}
