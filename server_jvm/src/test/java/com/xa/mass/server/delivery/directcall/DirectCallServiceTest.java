package com.xa.mass.server.delivery.directcall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime.WorkerCommandOfferStatus;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import com.xa.mass.server.api.v1.contract.delivery.directcall.DirectCallHttpContract.DirectCallRequest;
import com.xa.mass.server.api.v1.contract.delivery.directcall.DirectCallHttpContract.DirectCallResponse;
import com.xa.mass.server.api.v1.contract.delivery.directcall.DirectCallHttpContract.DirectTargetReason;
import com.xa.mass.server.api.v1.contract.delivery.directcall.DirectCallHttpContract.DirectTargetStatus;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.worker.binding.WorkerBindingProperties.EndpointProperties;
import com.xa.mass.server.worker.binding.WorkerBindingService;
import com.xa.mass.server.worker.binding.WorkerEndpointDirectory;
import com.xa.mass.server.worker.binding.WorkerTransportType;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;

class DirectCallServiceTest {

    private static final String GROUP_ID = "group-1";
    private static final String ADAPTER_ID = "adapter-1";
    private static final String OTHER_ADAPTER_ID = "adapter-2";

    private WorkerResourceCatalog catalog;
    private WorkerCommandRuntime commands;
    private WorkerBindingService bindings;
    private DirectCallService service;

    @BeforeEach
    void setUp() {
        catalog = mock(WorkerResourceCatalog.class);
        commands = mock(WorkerCommandRuntime.class);
        bindings = mock(WorkerBindingService.class);
        DirectCallProperties properties = new DirectCallProperties(
                3_000,
                10_000,
                1_000,
                10_000
        );
        service = new DirectCallService(
                catalog,
                commands,
                bindings,
                endpoints(),
                new DirectCallRegistry(properties),
                properties
        );
    }

    @Test
    void workerBatchUsesResourceBindingAndSharedMailboxOfferOnly() {
        List<String> workerIds = List.of(
                "worker-ok",
                "worker-missing",
                "worker-unbound",
                "worker-other-adapter"
        );
        when(catalog.getWorkerDescriptors(GROUP_ID, workerIds)).thenReturn(
                linkedMap(
                        workerIds,
                        Map.of(
                                "worker-ok", descriptor("worker-ok"),
                                "worker-unbound", descriptor("worker-unbound"),
                                "worker-other-adapter",
                                descriptor("worker-other-adapter")
                        )
                )
        );
        when(bindings.currentEndpointManagerIds(workerIds)).thenReturn(
                linkedMap(
                        workerIds,
                        Map.of(
                                "worker-ok", ADAPTER_ID,
                                "worker-other-adapter", OTHER_ADAPTER_ID
                        )
                )
        );
        when(commands.offerWorkerCommands(
                org.mockito.ArgumentMatchers.eq(ADAPTER_ID),
                anyMap()
        )).thenReturn(Map.of(
                "worker-ok",
                WorkerCommandOfferStatus.OFFERED
        ));

        var deferred = service.call(ADAPTER_ID, workerRequest(workerIds));
        @SuppressWarnings({"rawtypes", "unchecked"})
        org.mockito.ArgumentCaptor<Map<String, DeliveryCommand>> captor =
                (org.mockito.ArgumentCaptor) org.mockito.ArgumentCaptor
                        .forClass(Map.class);
        verify(commands).offerWorkerCommands(
                org.mockito.ArgumentMatchers.eq(ADAPTER_ID),
                captor.capture()
        );
        DeliveryCommand command = captor.getValue().get("worker-ok");
        assertThat(command.src()).isEqualTo(DeliveryEndpoint.SYSTEM);
        assertThat(command.dst()).isEqualTo(DeliveryEndpoint.WORKER);
        assertThat(command.forward()).startsWith(
                DirectCallRegistry.FORWARD_PREFIX
        );
        service.completeReports(
                ADAPTER_ID,
                List.of(workerReport(
                        "worker-ok",
                        command.messageType(),
                        command.forward()
                ))
        );

        DirectCallResponse response = response(deferred).getBody();
        assertThat(response).isNotNull();
        assertThat(response.results().keySet())
                .containsExactlyElementsOf(workerIds);
        assertThat(response.results().get("worker-ok").status())
                .isEqualTo(DirectTargetStatus.OBSERVED);
        assertReason(response, "worker-missing", DirectTargetReason.NOT_FOUND);
        assertReason(response, "worker-unbound", DirectTargetReason.NOT_BOUND);
        assertReason(
                response,
                "worker-other-adapter",
                DirectTargetReason.ENDPOINT_MISMATCH
        );
        verify(catalog).getWorkerDescriptors(GROUP_ID, workerIds);
        verify(bindings).currentEndpointManagerIds(workerIds);
    }

    @Test
    void occupiedSlotCompletesAsImmediateRejection() {
        prepareEligibleWorkers(List.of("worker-1"));
        when(commands.offerWorkerCommands(
                org.mockito.ArgumentMatchers.eq(ADAPTER_ID),
                anyMap()
        )).thenReturn(
                Map.of(
                        "worker-1",
                        WorkerCommandOfferStatus.OCCUPIED
                )
        );

        DirectCallResponse response = response(service.call(
                ADAPTER_ID,
                workerRequest(List.of("worker-1"))
        )).getBody();

        assertReason(
                response,
                "worker-1",
                DirectTargetReason.COMMAND_SLOT_OCCUPIED
        );
    }

    @Test
    void unknownSubmissionCompletesWithoutDeletingOrRetryingTheCommand() {
        prepareEligibleWorkers(List.of("worker-1"));
        when(commands.offerWorkerCommands(
                org.mockito.ArgumentMatchers.eq(ADAPTER_ID),
                anyMap()
        ))
                .thenThrow(new IllegalStateException("redis timeout"));

        DirectCallResponse response = response(service.call(
                ADAPTER_ID,
                workerRequest(List.of("worker-1"))
        )).getBody();

        assertThat(response.results().get("worker-1").status())
                .isEqualTo(DirectTargetStatus.UNOBSERVED);
        assertThat(response.results().get("worker-1").reason())
                .isEqualTo(DirectTargetReason.SUBMISSION_UNKNOWN);
        verify(commands).offerWorkerCommands(
                org.mockito.ArgumentMatchers.eq(ADAPTER_ID),
                anyMap()
        );
    }

    @Test
    void registryExistsBeforeOfferSoAnImmediateResultIsObserved() {
        prepareEligibleWorkers(List.of("worker-1"));
        when(commands.offerWorkerCommands(
                org.mockito.ArgumentMatchers.eq(ADAPTER_ID),
                anyMap()
        )).thenAnswer(
                invocation -> {
                    Map<String, DeliveryCommand> offered =
                            invocation.getArgument(1);
                    DeliveryCommand command = offered.get("worker-1");
                    service.completeReports(
                            ADAPTER_ID,
                            List.of(workerReport(
                                    "worker-1",
                                    command.messageType(),
                                    command.forward()
                            ))
                    );
                    return Map.of(
                            "worker-1",
                            WorkerCommandOfferStatus.OFFERED
                    );
                }
        );

        DirectCallResponse response = response(service.call(
                ADAPTER_ID,
                workerRequest(List.of("worker-1"))
        )).getBody();

        assertThat(response.results().get("worker-1").status())
                .isEqualTo(DirectTargetStatus.OBSERVED);
    }

    @Test
    void oneHundredWorkersUseOneBoundedOwnerReadAndOneOffer() {
        List<String> workerIds = IntStream.range(0, 100)
                .mapToObj(index -> "worker-" + index)
                .toList();
        prepareEligibleWorkers(workerIds);
        LinkedHashMap<String, WorkerCommandOfferStatus> statuses =
                new LinkedHashMap<>();
        workerIds.forEach(workerId -> statuses.put(
                workerId,
                WorkerCommandOfferStatus.OCCUPIED
        ));
        when(commands.offerWorkerCommands(
                org.mockito.ArgumentMatchers.eq(ADAPTER_ID),
                anyMap()
        ))
                .thenReturn(statuses);

        DirectCallResponse response = response(service.call(
                ADAPTER_ID,
                workerRequest(workerIds)
        )).getBody();

        assertThat(response.results()).hasSize(100);
        assertThat(response.results().keySet())
                .containsExactlyElementsOf(workerIds);
        verify(catalog).getWorkerDescriptors(GROUP_ID, workerIds);
        verify(bindings).currentEndpointManagerIds(workerIds);
        verify(commands).offerWorkerCommands(
                org.mockito.ArgumentMatchers.eq(ADAPTER_ID),
                anyMap()
        );
    }

    @Test
    void adapterCallStaysInTheServerFifoAndIsEventTransparent() {
        var deferred = service.call(
                ADAPTER_ID,
                new DirectCallRequest(
                        null,
                        null,
                        "adapter.vendor.inspect",
                        "{\"detail\":true}",
                        3_000L
                )
        );
        DeliveryCommand command = service.consumeAdapterCommands(
                ADAPTER_ID,
                100
        ).getFirst();
        assertThat(command.messageType()).isEqualTo("adapter.vendor.inspect");
        service.completeReports(
                ADAPTER_ID,
                List.of(DeliveryReport.create(
                        DeliveryEndpoint.ADAPTER,
                        ADAPTER_ID,
                        DeliveryEndpoint.SYSTEM,
                        command.messageType(),
                        "23005",
                        "unsupported",
                        command.forward()
                ))
        );

        DirectCallResponse response = response(deferred).getBody();
        assertThat(response.results().get(ADAPTER_ID).status())
                .isEqualTo(DirectTargetStatus.OBSERVED);
        verifyNoInteractions(catalog, commands, bindings);
    }

    @Test
    void ownerAdapterCallExposesOutcomeWithoutTheHttpContract() {
        DirectCallService.AdapterCallHandle handle =
                service.beginAdapterCall(
                        ADAPTER_ID,
                        "platform.adapter.worker-connections.snapshot",
                        "{\"workerIds\":[\"worker-1\"]}",
                        null
                );
        DeliveryCommand command = service.consumeAdapterCommands(
                ADAPTER_ID,
                100
        ).getFirst();
        service.completeReports(
                ADAPTER_ID,
                List.of(DeliveryReport.create(
                        DeliveryEndpoint.ADAPTER,
                        ADAPTER_ID,
                        DeliveryEndpoint.SYSTEM,
                        command.messageType(),
                        "200",
                        "{\"stateByWorkerId\":{\"worker-1\":\"CONNECTED\"}}",
                        command.forward()
                ))
        );

        DirectCallService.AdapterCallOutcome outcome = handle.completion()
                .toCompletableFuture()
                .join();
        assertThat(outcome.observed()).isTrue();
        assertThat(outcome.outcomeCode()).isEqualTo("200");
        assertThat(outcome.opaqueResultPayload()).contains("CONNECTED");
        assertThat(handle.timeoutMillis()).isEqualTo(3_000);
        verifyNoInteractions(catalog, commands, bindings);
    }

    @Test
    void invalidShapeAndOwnerReadFailureKeepHttpErrorSemantics() {
        assertThatThrownBy(() -> service.call(
                ADAPTER_ID,
                new DirectCallRequest(
                        GROUP_ID,
                        Map.of(),
                        "event",
                        null,
                        3_000L
                )
        )).isInstanceOfSatisfying(ServerException.class, error ->
                assertThat(error.errorCode()).isEqualTo(
                        ServerErrorCode.INVALID_DIRECT_CALL_REQUEST
                ));

        List<String> workerIds = List.of("worker-1");
        when(catalog.getWorkerDescriptors(GROUP_ID, workerIds))
                .thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> service.call(
                ADAPTER_ID,
                workerRequest(workerIds)
        )).isInstanceOfSatisfying(ServerException.class, error ->
                assertThat(error.errorCode()).isEqualTo(
                        ServerErrorCode.DIRECT_CALL_UNAVAILABLE
                ));
    }

    private void prepareEligibleWorkers(List<String> workerIds) {
        LinkedHashMap<String, WorkerDescriptor> workers = new LinkedHashMap<>();
        LinkedHashMap<String, String> endpointIds = new LinkedHashMap<>();
        workerIds.forEach(workerId -> {
            workers.put(workerId, descriptor(workerId));
            endpointIds.put(workerId, ADAPTER_ID);
        });
        when(catalog.getWorkerDescriptors(GROUP_ID, workerIds))
                .thenReturn(workers);
        when(bindings.currentEndpointManagerIds(workerIds))
                .thenReturn(endpointIds);
    }

    private static DirectCallRequest workerRequest(List<String> workerIds) {
        LinkedHashMap<String, String> payloads = new LinkedHashMap<>();
        workerIds.forEach(workerId -> payloads.put(
                workerId,
                "{\"workerId\":\"" + workerId + "\"}"
        ));
        return new DirectCallRequest(
                GROUP_ID,
                payloads,
                "extension.worker.device.inspect",
                null,
                3_000L
        );
    }

    private static WorkerDescriptor descriptor(String workerId) {
        return new WorkerDescriptor(
                workerId,
                GROUP_ID,
                ADAPTER_ID
        );
    }

    private static DeliveryReport workerReport(
            String workerId,
            String messageType,
            String forward
    ) {
        return DeliveryReport.create(
                DeliveryEndpoint.WORKER,
                workerId,
                DeliveryEndpoint.SYSTEM,
                messageType,
                "200",
                "{\"reachable\":true}",
                forward
        );
    }

    private static WorkerEndpointDirectory endpoints() {
        return new WorkerEndpointDirectory(Map.of(
                ADAPTER_ID,
                new EndpointProperties(
                        WorkerTransportType.WEBSOCKET,
                        URI.create("ws://127.0.0.1:18083/worker")
                ),
                OTHER_ADAPTER_ID,
                new EndpointProperties(
                        WorkerTransportType.WEBSOCKET,
                        URI.create("ws://127.0.0.1:18084/worker")
                ),
                "system-polling",
                new EndpointProperties(
                        WorkerTransportType.POLLING,
                        URI.create("http://127.0.0.1:18082")
                )
        ));
    }

    private static <T> LinkedHashMap<String, T> linkedMap(
            List<String> keys,
            Map<String, T> values
    ) {
        LinkedHashMap<String, T> result = new LinkedHashMap<>();
        keys.forEach(key -> result.put(key, values.get(key)));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static ResponseEntity<DirectCallResponse> response(
            DeferredResult<ResponseEntity<DirectCallResponse>> deferred
    ) {
        return (ResponseEntity<DirectCallResponse>) deferred.getResult();
    }

    private static void assertReason(
            DirectCallResponse response,
            String targetId,
            DirectTargetReason reason
    ) {
        assertThat(response.results().get(targetId).status())
                .isEqualTo(DirectTargetStatus.REJECTED);
        assertThat(response.results().get(targetId).reason())
                .isEqualTo(reason);
    }
}
