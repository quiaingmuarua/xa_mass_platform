package com.xa.mass.server.workerdelivery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.delivery.WorkerResultRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.server.workerbinding.WorkerBindingService;
import com.xa.mass.server.control.ControlCallService;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerDeliveryServiceTest {

    private static final String COMMAND_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";
    private static final String POLLING =
            WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID;
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private WorkerCommandRuntime commandRuntime;
    private WorkerResultRuntime resultRuntime;
    private WorkerBindingService bindings;
    private ControlCallService controlCalls;
    private WorkerDeliveryService service;

    @BeforeEach
    void setUp() {
        commandRuntime = mock(WorkerCommandRuntime.class);
        resultRuntime = mock(WorkerResultRuntime.class);
        bindings = mock(WorkerBindingService.class);
        controlCalls = mock(ControlCallService.class);
        when(controlCalls.consumeAdapterCommands(anyString(), anyInt()))
                .thenReturn(List.of());
        when(controlCalls.consumeWorkerCommands(anyString(), anyInt()))
                .thenReturn(Map.of());
        when(controlCalls.completeReports(anyString(), anyList()))
                .thenAnswer(invocation ->
                        new ControlCallService.ResultAppendCounts(
                                invocation.<List<?>>getArgument(1).size(),
                                0
                        ));
        service = new WorkerDeliveryService(
                commandRuntime,
                resultRuntime,
                bindings,
                controlCalls
        );
    }

    @Test
    void pointPollDropsACommandThatExpiredAfterRedisConsumption() {
        when(commandRuntime.consumeWorkerCommand(POLLING, "worker-1"))
                .thenReturn(DeliveryCommand.create(
                        DeliveryEndpoint.TASK,
                        DeliveryEndpoint.WORKER,
                        "test.event",
                        System.currentTimeMillis() - 1,
                        "item",
                        "context"
                ));

        assertThat(service.pollWorkerCommand(POLLING, "worker-1"))
                .isNull();
        verify(bindings).requireCurrentEndpoint(POLLING, "worker-1");
    }

    @Test
    void adapterCommandsFillTheLimitBeforeAnyWorkerSourceIsRead() {
        DeliveryCommand first = DeliveryCommand.create(
                DeliveryEndpoint.SYSTEM,
                DeliveryEndpoint.ADAPTER,
                "platform.adapter.probe",
                System.currentTimeMillis() + 10_000,
                "null",
                "control-only:v1:first"
        );
        DeliveryCommand second = DeliveryCommand.create(
                DeliveryEndpoint.SYSTEM,
                DeliveryEndpoint.ADAPTER,
                "platform.adapter.events.snapshot",
                System.currentTimeMillis() + 10_000,
                "null",
                "control-only:v1:second"
        );
        when(controlCalls.consumeAdapterCommands("endpoint-1", 2))
                .thenReturn(List.of(first, second));

        Map<String, DeliveryCommand> commands =
                service.consumeWorkerCommands("endpoint-1", 2);

        assertThat(commands.values()).containsExactly(first, second);
        assertThat(commands.keySet()).hasSize(2);
        verify(controlCalls, never()).consumeWorkerCommands(
                anyString(),
                anyInt()
        );
        verify(commandRuntime, never()).consumeWorkerCommands(
                anyString(),
                anyInt()
        );
    }

    @Test
    void adapterPrefixUsesRemainingLimitFromControlWorkerHashOnly() {
        DeliveryCommand adapter = DeliveryCommand.create(
                DeliveryEndpoint.SYSTEM,
                DeliveryEndpoint.ADAPTER,
                "platform.adapter.probe",
                System.currentTimeMillis() + 10_000,
                "null",
                "control-only:v1:adapter"
        );
        DeliveryCommand control = DeliveryCommand.create(
                DeliveryEndpoint.SYSTEM,
                DeliveryEndpoint.WORKER,
                "platform.worker.properties.snapshot",
                System.currentTimeMillis() + 10_000,
                "{}",
                "control-only:v1:test"
        );
        when(controlCalls.consumeAdapterCommands("endpoint-1", 4))
                .thenReturn(List.of(adapter));
        when(controlCalls.consumeWorkerCommands("endpoint-1", 3))
                .thenReturn(Map.of("worker-1", control));

        assertThat(service.consumeWorkerCommands("endpoint-1", 4).values())
                .containsExactly(adapter, control);
        verify(commandRuntime, never()).consumeWorkerCommands(
                anyString(),
                anyInt()
        );
    }

    @Test
    void emptyControlWorkerHashFallsBackToTaskWithTheRemainingLimit() {
        DeliveryCommand adapter = DeliveryCommand.create(
                DeliveryEndpoint.SYSTEM,
                DeliveryEndpoint.ADAPTER,
                "platform.adapter.probe",
                System.currentTimeMillis() + 10_000,
                "null",
                "control-only:v1:adapter"
        );
        DeliveryCommand task = DeliveryCommand.create(
                DeliveryEndpoint.TASK,
                DeliveryEndpoint.WORKER,
                "test.event",
                System.currentTimeMillis() + 10_000,
                "{}",
                "task-context"
        );
        when(controlCalls.consumeAdapterCommands("endpoint-1", 4))
                .thenReturn(List.of(adapter));
        when(commandRuntime.consumeWorkerCommands("endpoint-1", 3))
                .thenReturn(Map.of("entry:0", task));

        Map<String, DeliveryCommand> commands =
                service.consumeWorkerCommands("endpoint-1", 4);

        assertThat(commands).containsEntry("entry:0", task);
        assertThat(commands).containsValue(adapter).hasSize(2);
        assertThat(commands.entrySet().stream()
                .filter(entry -> entry.getValue().equals(adapter))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow()).isNotEqualTo("entry:0");
        verify(controlCalls).consumeWorkerCommands("endpoint-1", 3);
    }

    @Test
    void acquiredAdapterCommandsSurviveLowerPrioritySourceFailure() {
        DeliveryCommand adapter = DeliveryCommand.create(
                DeliveryEndpoint.SYSTEM,
                DeliveryEndpoint.ADAPTER,
                "platform.adapter.probe",
                System.currentTimeMillis() + 10_000,
                "null",
                "control-only:v1:adapter"
        );
        when(controlCalls.consumeAdapterCommands("endpoint-1", 2))
                .thenReturn(List.of(adapter));
        when(controlCalls.consumeWorkerCommands("endpoint-1", 1))
                .thenThrow(new IllegalStateException("unavailable"));

        Map<String, DeliveryCommand> commands =
                service.consumeWorkerCommands("endpoint-1", 2);

        assertThat(commands.values()).containsExactly(adapter);
        verify(commandRuntime, never()).consumeWorkerCommands(
                anyString(),
                anyInt()
        );
    }

    @Test
    void workerResultRejectsAdapterEvidence() {
        DeliveryReport result = result(COMMAND_ID, "23002");

        assertThatThrownBy(() -> service.appendWorkerResult(
                POLLING,
                "worker-1",
                result
        ))
                .isInstanceOf(ServerException.class)
                .extracting(
                        error -> ((ServerException) error).errorCode()
                )
                .isEqualTo(
                        ServerErrorCode.INVALID_WORKER_DELIVERY_REQUEST
                );
        verify(resultRuntime, never()).appendWorkerResults(List.of(result));
        verify(bindings).requireCurrentEndpoint(POLLING, "worker-1");
    }

    @Test
    void pointResultRejectsAnotherWorkerSourceId() {
        DeliveryReport result = DeliveryReport.create(
                DeliveryEndpoint.WORKER,
                "worker-2",
                DeliveryEndpoint.TASK,
                "test.event",
                "200",
                "null",
                "context"
        );

        assertThatThrownBy(() -> service.appendWorkerResult(
                POLLING,
                "worker-1",
                result
        )).isInstanceOf(ServerException.class);
        verify(resultRuntime, never()).appendWorkerResults(List.of(result));
    }

    @Test
    void adapterBatchAcceptsAllResultClassesAndRejectsMalformedItems() {
        DeliveryReport success = result(COMMAND_ID, "200");
        DeliveryReport failure = result(
                "9f0d983c-8010-4d59-a6d2-e8fedb8d0059",
                "3500"
        );
        DeliveryReport forgedRejection = result(
                "66f60ac8-e68f-4783-90e3-13b20a54ca13",
                "23002"
        );
        List<DeliveryReport> accepted = List.of(
                success,
                failure,
                forgedRejection
        );
        when(resultRuntime.appendWorkerResults(accepted))
                .thenReturn(accepted.size());

        var counts = service.appendAdapterResults(
                "endpoint-1",
                java.util.Arrays.asList(
                        codec.encodeDeliveryReport(success),
                        "not-json",
                        null,
                        codec.encodeDeliveryReport(failure),
                        codec.encodeDeliveryReport(forgedRejection)
                )
        );

        assertThat(counts.acceptedCount()).isEqualTo(3);
        assertThat(counts.rejectedCount()).isEqualTo(2);
        verify(resultRuntime).appendWorkerResults(accepted);
    }

    @Test
    void adapterBatchRejectsAResultForAnotherDestination() {
        DeliveryReport success = result(COMMAND_ID, "200");
        DeliveryReport wrongDestination = DeliveryReport.create(
                DeliveryEndpoint.ADAPTER,
                "endpoint-1",
                DeliveryEndpoint.SYSTEM,
                "test.event",
                "23002",
                "null",
                "context"
        );
        when(resultRuntime.appendWorkerResults(List.of(success)))
                .thenReturn(1);
        when(controlCalls.completeReports(
                "endpoint-1",
                List.of(wrongDestination)
        )).thenReturn(new ControlCallService.ResultAppendCounts(0, 1));

        var counts = service.appendAdapterResults(
                "endpoint-1",
                List.of(
                        codec.encodeDeliveryReport(success),
                        codec.encodeDeliveryReport(wrongDestination)
                )
        );

        assertThat(counts.acceptedCount()).isEqualTo(1);
        assertThat(counts.rejectedCount()).isEqualTo(1);
        verify(resultRuntime).appendWorkerResults(List.of(success));
    }

    @Test
    void adapterBatchRejectsAnotherAdapterSourceId() {
        DeliveryReport success = result(COMMAND_ID, "200");
        DeliveryReport foreignAdapter = DeliveryReport.create(
                DeliveryEndpoint.ADAPTER,
                "endpoint-2",
                DeliveryEndpoint.TASK,
                "test.event",
                "23002",
                "null",
                "context"
        );
        when(resultRuntime.appendWorkerResults(List.of(success)))
                .thenReturn(1);

        var counts = service.appendAdapterResults(
                "endpoint-1",
                List.of(
                        codec.encodeDeliveryReport(success),
                        codec.encodeDeliveryReport(foreignAdapter)
                )
        );

        assertThat(counts.acceptedCount()).isEqualTo(1);
        assertThat(counts.rejectedCount()).isEqualTo(1);
        verify(resultRuntime).appendWorkerResults(List.of(success));
    }

    @Test
    void allInvalidItemsDoNotCallTheRuntime() {
        var counts = service.appendAdapterResults(
                "endpoint-1",
                List.of(
                        "not-json",
                        ""
                )
        );

        assertThat(counts.acceptedCount()).isZero();
        assertThat(counts.rejectedCount()).isEqualTo(2);
        verify(resultRuntime, never()).appendWorkerResults(
                org.mockito.ArgumentMatchers.anyList()
        );
    }

    @Test
    void incompleteRuntimeAppendIsUnavailableForRetry() {
        DeliveryReport success = result(COMMAND_ID, "200");
        when(resultRuntime.appendWorkerResults(List.of(success)))
                .thenReturn(0);

        assertThatThrownBy(() -> service.appendAdapterResults(
                "endpoint-1",
                List.of(codec.encodeDeliveryReport(success))
        ))
                .isInstanceOf(ServerException.class)
                .extracting(
                        error -> ((ServerException) error).errorCode()
                )
                .isEqualTo(
                        ServerErrorCode.WORKER_DELIVERY_UNAVAILABLE
                );
    }

    @Test
    void systemPollingCannotUseAdapterBatchOperations() {
        assertThatThrownBy(() -> service.appendAdapterResults(
                WorkerDeliveryProtocol
                        .SYSTEM_POLLING_ENDPOINT_MANAGER_ID,
                List.of(codec.encodeDeliveryReport(
                        result(COMMAND_ID, "200")
                ))
        )).isInstanceOf(ServerException.class);
    }

    private static DeliveryReport result(
            String messageId,
            String outcomeCode
    ) {
        DeliveryEndpoint source = !"200".equals(outcomeCode)
                && outcomeCode.startsWith("2")
                ? DeliveryEndpoint.ADAPTER
                : DeliveryEndpoint.WORKER;
        return DeliveryReport.create(
                source,
                source == DeliveryEndpoint.ADAPTER
                        ? "endpoint-1"
                        : "worker-1",
                DeliveryEndpoint.TASK,
                "test.event",
                outcomeCode,
                "null",
                "context"
        );
    }
}
