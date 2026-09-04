package com.xa.mass.server.delivery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.delivery.TaskResultRuntime;
import com.xa.mass.kernel.delivery.TaskResultRuntime.TaskResultClass;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.server.worker.binding.WorkerBindingService;
import com.xa.mass.server.delivery.directcall.DirectCallService;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerDeliveryServiceTest {

    private static final String COMMAND_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";
    private static final String POLLING =
            WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID;
    private WorkerCommandRuntime commandRuntime;
    private TaskResultRuntime resultRuntime;
    private WorkerBindingService bindings;
    private DirectCallService directCalls;
    private WorkerServiceabilityRuntime serviceability;
    private WorkerDeliveryService service;

    @BeforeEach
    void setUp() {
        commandRuntime = mock(WorkerCommandRuntime.class);
        resultRuntime = mock(TaskResultRuntime.class);
        bindings = mock(WorkerBindingService.class);
        directCalls = mock(DirectCallService.class);
        serviceability = mock(WorkerServiceabilityRuntime.class);
        when(serviceability.consumeProbeRequests(anyString(), anyInt()))
                .thenReturn(List.of());
        when(directCalls.consumeAdapterCommands(anyString(), anyInt()))
                .thenReturn(List.of());
        when(directCalls.completeReports(anyString(), anyList()))
                .thenAnswer(invocation ->
                        new DirectCallService.ResultAppendCounts(
                                invocation.<List<?>>getArgument(1).size(),
                                0
                        ));
        service = new WorkerDeliveryService(
                commandRuntime,
                resultRuntime,
                bindings,
                directCalls,
                serviceability
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
                "direct-call:v1:first"
        );
        DeliveryCommand second = DeliveryCommand.create(
                DeliveryEndpoint.SYSTEM,
                DeliveryEndpoint.ADAPTER,
                "platform.adapter.events.snapshot",
                System.currentTimeMillis() + 10_000,
                "null",
                "direct-call:v1:second"
        );
        when(directCalls.consumeAdapterCommands("endpoint-1", 2))
                .thenReturn(List.of(first, second));

        Map<String, DeliveryCommand> commands =
                service.consumeWorkerCommands("endpoint-1", 2);

        assertThat(commands.values()).containsExactly(first, second);
        assertThat(commands.keySet()).hasSize(2);
        verify(commandRuntime, never()).consumeWorkerCommands(
                anyString(),
                anyInt()
        );
        verify(serviceability, never()).consumeProbeRequests(
                anyString(),
                anyInt()
        );
    }

    @Test
    void adapterPrefixUsesRemainingLimitFromSharedWorkerHash() {
        DeliveryCommand adapter = DeliveryCommand.create(
                DeliveryEndpoint.SYSTEM,
                DeliveryEndpoint.ADAPTER,
                "platform.adapter.probe",
                System.currentTimeMillis() + 10_000,
                "null",
                "direct-call:v1:adapter"
        );
        DeliveryCommand control = DeliveryCommand.create(
                DeliveryEndpoint.SYSTEM,
                DeliveryEndpoint.WORKER,
                "platform.worker.properties.snapshot",
                System.currentTimeMillis() + 10_000,
                "{}",
                "direct-call:v1:test"
        );
        when(directCalls.consumeAdapterCommands("endpoint-1", 4))
                .thenReturn(List.of(adapter));
        when(commandRuntime.consumeWorkerCommands("endpoint-1", 3))
                .thenReturn(Map.of("worker-1", control));

        assertThat(service.consumeWorkerCommands("endpoint-1", 4).values())
                .containsExactly(adapter, control);
        verify(commandRuntime).consumeWorkerCommands("endpoint-1", 3);
    }

    @Test
    void sharedWorkerHashUsesTheRemainingLimitOnce() {
        DeliveryCommand adapter = DeliveryCommand.create(
                DeliveryEndpoint.SYSTEM,
                DeliveryEndpoint.ADAPTER,
                "platform.adapter.probe",
                System.currentTimeMillis() + 10_000,
                "null",
                "direct-call:v1:adapter"
        );
        DeliveryCommand task = DeliveryCommand.create(
                DeliveryEndpoint.TASK,
                DeliveryEndpoint.WORKER,
                "test.event",
                System.currentTimeMillis() + 10_000,
                "{}",
                "task-context"
        );
        when(directCalls.consumeAdapterCommands("endpoint-1", 4))
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
        verify(commandRuntime).consumeWorkerCommands("endpoint-1", 3);
    }

    @Test
    void workerCommandsFillTheLimitBeforeProbeRequestsAreRead() {
        DeliveryCommand task = DeliveryCommand.create(
                DeliveryEndpoint.TASK,
                DeliveryEndpoint.WORKER,
                "test.event",
                System.currentTimeMillis() + 10_000,
                "{}",
                "task-context"
        );
        when(commandRuntime.consumeWorkerCommands("endpoint-1", 1))
                .thenReturn(Map.of("worker-1", task));

        assertThat(service.consumeWorkerCommands("endpoint-1", 1))
                .containsExactly(Map.entry("worker-1", task));

        verify(serviceability, never()).consumeProbeRequests(
                anyString(),
                anyInt()
        );
    }

    @Test
    void remainingCapacityAddsOneLowPriorityServiceabilityCommand() {
        DeliveryCommand task = DeliveryCommand.create(
                DeliveryEndpoint.TASK,
                DeliveryEndpoint.WORKER,
                "test.event",
                System.currentTimeMillis() + 10_000,
                "{}",
                "task-context"
        );
        when(commandRuntime.consumeWorkerCommands("endpoint-1", 3))
                .thenReturn(Map.of("worker-1", task));
        when(serviceability.consumeProbeRequests("endpoint-1", 100))
                .thenReturn(List.of("worker-2", "worker-3"));
        long before = System.currentTimeMillis();

        Map<String, DeliveryCommand> commands =
                service.consumeWorkerCommands("endpoint-1", 3);

        assertThat(commands).hasSize(2).containsEntry("worker-1", task);
        DeliveryCommand probe = commands.values().stream()
                .filter(command -> command.src() == DeliveryEndpoint.KERNEL)
                .findFirst()
                .orElseThrow();
        assertThat(probe.dst()).isEqualTo(DeliveryEndpoint.ADAPTER);
        assertThat(probe.messageType()).isEqualTo(
                "platform.adapter.worker-connections.snapshot"
        );
        assertThat(probe.payload()).isEqualTo(
                "{\"workerIds\":[\"worker-2\",\"worker-3\"]}"
        );
        assertThat(probe.forward()).startsWith(
                "worker-serviceability:v1:"
        );
        long checkStartedAt = Long.parseLong(probe.forward().substring(
                "worker-serviceability:v1:".length()
        ));
        assertThat(checkStartedAt).isBetween(
                before,
                System.currentTimeMillis()
        );
        assertThat(probe.executeBeforeMillis())
                .isEqualTo(checkStartedAt + 5_000L);
    }

    @Test
    void probeRuntimeFailureDoesNotDiscardHigherPriorityCommands() {
        DeliveryCommand task = DeliveryCommand.create(
                DeliveryEndpoint.TASK,
                DeliveryEndpoint.WORKER,
                "test.event",
                System.currentTimeMillis() + 10_000,
                "{}",
                "task-context"
        );
        when(commandRuntime.consumeWorkerCommands("endpoint-1", 2))
                .thenReturn(Map.of("worker-1", task));
        when(serviceability.consumeProbeRequests("endpoint-1", 100))
                .thenThrow(new IllegalStateException("unavailable"));

        assertThat(service.consumeWorkerCommands("endpoint-1", 2))
                .containsExactly(Map.entry("worker-1", task));
    }

    @Test
    void acquiredAdapterCommandsSurviveLowerPrioritySourceFailure() {
        DeliveryCommand adapter = DeliveryCommand.create(
                DeliveryEndpoint.SYSTEM,
                DeliveryEndpoint.ADAPTER,
                "platform.adapter.probe",
                System.currentTimeMillis() + 10_000,
                "null",
                "direct-call:v1:adapter"
        );
        when(directCalls.consumeAdapterCommands("endpoint-1", 2))
                .thenReturn(List.of(adapter));
        when(commandRuntime.consumeWorkerCommands("endpoint-1", 1))
                .thenThrow(new IllegalStateException("unavailable"));

        Map<String, DeliveryCommand> commands =
                service.consumeWorkerCommands("endpoint-1", 2);

        assertThat(commands.values()).containsExactly(adapter);
        verify(commandRuntime).consumeWorkerCommands("endpoint-1", 1);
    }

    @Test
    void pointWorkerResultsAreMappedToSuccessAndFailureLanes() {
        DeliveryReport success = result(COMMAND_ID, "200");
        DeliveryReport failure = result(
                "9f0d983c-8010-4d59-a6d2-e8fedb8d0059",
                "3500"
        );
        when(resultRuntime.appendTaskResults(
                TaskResultClass.SUCCESS,
                List.of(success)
        )).thenReturn(1);
        when(resultRuntime.appendTaskResults(
                TaskResultClass.FAILURE,
                List.of(failure)
        )).thenReturn(1);

        service.appendWorkerResult(POLLING, "worker-1", success);
        service.appendWorkerResult(POLLING, "worker-1", failure);

        verify(resultRuntime).appendTaskResults(
                TaskResultClass.SUCCESS,
                List.of(success)
        );
        verify(resultRuntime).appendTaskResults(
                TaskResultClass.FAILURE,
                List.of(failure)
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
        verify(resultRuntime, never()).appendTaskResults(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList()
        );
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
        verify(resultRuntime, never()).appendTaskResults(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList()
        );
    }

    @Test
    void taskBatchAcceptsSuccessFailureAndAdapterRejection() {
        DeliveryReport success = result(COMMAND_ID, "200");
        DeliveryReport failure = result(
                "9f0d983c-8010-4d59-a6d2-e8fedb8d0059",
                "3500"
        );
        DeliveryReport forgedRejection = result(
                "66f60ac8-e68f-4783-90e3-13b20a54ca13",
                "23002"
        );
        when(resultRuntime.appendTaskResults(
                TaskResultClass.SUCCESS,
                List.of(success)
        )).thenReturn(1);
        when(resultRuntime.appendTaskResults(
                TaskResultClass.FAILURE,
                List.of(failure, forgedRejection)
        )).thenReturn(2);

        var counts = service.appendAdapterReports(
                "endpoint-1",
                List.of(
                        success,
                        failure,
                        forgedRejection
                )
        );

        assertThat(counts.acceptedCount()).isEqualTo(3);
        assertThat(counts.rejectedCount()).isZero();
        verify(resultRuntime).appendTaskResults(
                TaskResultClass.SUCCESS,
                List.of(success)
        );
        verify(resultRuntime).appendTaskResults(
                TaskResultClass.FAILURE,
                List.of(failure, forgedRejection)
        );
    }

    @Test
    void mixedDestinationBatchFailsBeforeOwnerSideEffects() {
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
        assertThatThrownBy(() -> service.appendAdapterReports(
                "endpoint-1",
                List.of(success, wrongDestination)
        )).isInstanceOf(ServerException.class)
                .extracting(error -> ((ServerException) error).errorCode())
                .isEqualTo(ServerErrorCode.INVALID_WORKER_DELIVERY_REQUEST);
        verifyNoInteractions(resultRuntime, directCalls, serviceability);
    }

    @Test
    void unsupportedDestinationBatchFailsBeforeOwnerSideEffects() {
        DeliveryReport unsupported = DeliveryReport.create(
                DeliveryEndpoint.WORKER,
                "worker-1",
                DeliveryEndpoint.ADAPTER,
                "test.event",
                "200",
                "null",
                "context"
        );

        assertThatThrownBy(() -> service.appendAdapterReports(
                "endpoint-1",
                List.of(unsupported)
        )).isInstanceOf(ServerException.class)
                .extracting(error -> ((ServerException) error).errorCode())
                .isEqualTo(ServerErrorCode.INVALID_WORKER_DELIVERY_REQUEST);
        verifyNoInteractions(resultRuntime, directCalls, serviceability);
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
        when(resultRuntime.appendTaskResults(
                TaskResultClass.SUCCESS,
                List.of(success)
        )).thenReturn(1);

        var counts = service.appendAdapterReports(
                "endpoint-1",
                List.of(success, foreignAdapter)
        );

        assertThat(counts.acceptedCount()).isEqualTo(1);
        assertThat(counts.rejectedCount()).isEqualTo(1);
        verify(resultRuntime).appendTaskResults(
                TaskResultClass.SUCCESS,
                List.of(success)
        );
    }

    @Test
    void kernelResultCapacityFailureIsUnavailableForBatchRetry() {
        DeliveryReport kernel = DeliveryReport.create(
                DeliveryEndpoint.ADAPTER,
                "endpoint-1",
                DeliveryEndpoint.KERNEL,
                "platform.adapter.worker-connections.snapshot",
                "200",
                "{\"stateByWorkerId\":{\"worker-1\":\"CONNECTED\"}}",
                "worker-serviceability:v1:123"
        );
        when(serviceability.appendAdapterEvidenceResults(List.of(kernel)))
                .thenReturn(0);

        assertThatThrownBy(() -> service.appendAdapterReports(
                "endpoint-1",
                List.of(kernel)
        ))
                .isInstanceOf(ServerException.class)
                .extracting(error -> ((ServerException) error).errorCode())
                .isEqualTo(ServerErrorCode.WORKER_DELIVERY_UNAVAILABLE);
    }

    @Test
    void homogeneousBatchesRouteOnlyToTheirOwner() {
        DeliveryReport task = result(COMMAND_ID, "200");
        DeliveryReport direct = DeliveryReport.create(
                DeliveryEndpoint.WORKER,
                "worker-1",
                DeliveryEndpoint.SYSTEM,
                "platform.worker.probe",
                "200",
                "{}",
                "direct-call:v1:test"
        );
        DeliveryReport kernel = DeliveryReport.create(
                DeliveryEndpoint.ADAPTER,
                "endpoint-1",
                DeliveryEndpoint.KERNEL,
                "platform.adapter.worker-connections.snapshot",
                "200",
                "{\"stateByWorkerId\":{\"worker-1\":\"CONNECTED\"}}",
                "worker-serviceability:v1:123"
        );
        DeliveryReport routeChange = DeliveryReport.create(
                DeliveryEndpoint.ADAPTER,
                "endpoint-1",
                DeliveryEndpoint.KERNEL,
                "platform.adapter.worker-connection.changed",
                "200",
                "{\"workerId\":\"worker-1\",\"state\":\"CONNECTED\","
                        + "\"observedAtMillis\":123}",
                "worker-serviceability-evidence:v1"
        );
        DeliveryReport unknownSystem = DeliveryReport.create(
                DeliveryEndpoint.ADAPTER,
                "endpoint-1",
                DeliveryEndpoint.SYSTEM,
                "platform.adapter.unknown",
                "200",
                "{}",
                "unknown"
        );
        when(resultRuntime.appendTaskResults(
                TaskResultClass.SUCCESS,
                List.of(task)
        )).thenReturn(1);
        when(directCalls.completeReports(
                "endpoint-1",
                List.of(direct, unknownSystem)
        )).thenReturn(new DirectCallService.ResultAppendCounts(1, 1));
        when(serviceability.appendAdapterEvidenceResults(List.of(
                kernel,
                routeChange
        ))).thenReturn(2);

        var taskCounts = service.appendAdapterReports(
                "endpoint-1",
                List.of(task)
        );
        var systemCounts = service.appendAdapterReports(
                "endpoint-1",
                List.of(direct, unknownSystem)
        );
        var kernelCounts = service.appendAdapterReports(
                "endpoint-1",
                List.of(kernel, routeChange)
        );

        assertThat(taskCounts).isEqualTo(new WorkerDeliveryService
                .WorkerResultAppendCounts(1, 0));
        assertThat(systemCounts).isEqualTo(new WorkerDeliveryService
                .WorkerResultAppendCounts(1, 1));
        assertThat(kernelCounts).isEqualTo(new WorkerDeliveryService
                .WorkerResultAppendCounts(2, 0));
        verify(resultRuntime).appendTaskResults(
                TaskResultClass.SUCCESS,
                List.of(task)
        );
        verify(directCalls).completeReports(
                "endpoint-1",
                List.of(direct, unknownSystem)
        );
        verify(serviceability).appendAdapterEvidenceResults(List.of(
                kernel,
                routeChange
        ));
    }

    @Test
    void adapterBatchAcceptsOneHundredKernelReports() {
        DeliveryReport kernel = DeliveryReport.create(
                DeliveryEndpoint.ADAPTER,
                "endpoint-1",
                DeliveryEndpoint.KERNEL,
                "platform.adapter.worker-connection.changed",
                "200",
                "{\"workerId\":\"worker-1\",\"state\":\"CONNECTED\","
                        + "\"observedAtMillis\":123}",
                "worker-serviceability-evidence:v1"
        );
        List<DeliveryReport> reports = Collections.nCopies(100, kernel);
        when(serviceability.appendAdapterEvidenceResults(reports))
                .thenReturn(100);

        var counts = service.appendAdapterReports(
                "endpoint-1",
                Collections.nCopies(100, kernel)
        );

        assertThat(counts.acceptedCount()).isEqualTo(100);
        assertThat(counts.rejectedCount()).isZero();
        verify(serviceability).appendAdapterEvidenceResults(reports);
    }

    @Test
    void oversizedAdapterBatchFailsBeforeOwnerSideEffects() {
        assertThatThrownBy(() -> service.appendAdapterReports(
                "endpoint-1",
                Collections.nCopies(101, result(COMMAND_ID, "200"))
        ))
                .isInstanceOf(ServerException.class)
                .extracting(error -> ((ServerException) error).errorCode())
                .isEqualTo(ServerErrorCode.INVALID_WORKER_DELIVERY_REQUEST);

        verifyNoInteractions(resultRuntime, directCalls, serviceability);
    }

    @Test
    void foreignKernelItemsAreRejectedWithoutOwnerSideEffects() {
        DeliveryReport foreign = DeliveryReport.create(
                DeliveryEndpoint.ADAPTER,
                "endpoint-2",
                DeliveryEndpoint.KERNEL,
                "platform.adapter.worker-connection.changed",
                "200",
                "{}",
                "worker-serviceability-evidence:v1"
        );

        var counts = service.appendAdapterReports(
                "endpoint-1",
                List.of(foreign)
        );

        assertThat(counts.acceptedCount()).isZero();
        assertThat(counts.rejectedCount()).isEqualTo(1);
        verifyNoInteractions(resultRuntime, directCalls, serviceability);
    }

    @Test
    void incompleteRuntimeAppendIsUnavailableForRetry() {
        DeliveryReport success = result(COMMAND_ID, "200");
        when(resultRuntime.appendTaskResults(
                TaskResultClass.SUCCESS,
                List.of(success)
        )).thenReturn(0);

        assertThatThrownBy(() -> service.appendAdapterReports(
                "endpoint-1",
                List.of(success)
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
        assertThatThrownBy(() -> service.appendAdapterReports(
                WorkerDeliveryProtocol
                        .SYSTEM_POLLING_ENDPOINT_MANAGER_ID,
                List.of(result(COMMAND_ID, "200"))
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
