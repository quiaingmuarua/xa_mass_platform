package com.xa.mass.server.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreState;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import com.xa.mass.server.api.v1.control.ControlCallHttpContract.ControlBatchCallResponse;
import com.xa.mass.server.api.v1.control.ControlCallHttpContract.ControlCallRequest;
import com.xa.mass.server.api.v1.control.ControlCallHttpContract.ControlTargetReason;
import com.xa.mass.server.api.v1.control.ControlCallHttpContract.ControlTargetStatus;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.workerbinding.WorkerBindingProperties.EndpointProperties;
import com.xa.mass.server.workerbinding.WorkerBindingService;
import com.xa.mass.server.workerbinding.WorkerEndpointDirectory;
import com.xa.mass.server.workerbinding.WorkerTransportType;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
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

class ControlCallServiceTest {

    private static final String GROUP_ID = "group-1";
    private static final String ADAPTER_ID = "adapter-1";
    private static final String OTHER_ADAPTER_ID = "adapter-2";

    private WorkerResourceCatalog catalog;
    private WorkerScoreCore scores;
    private WorkerBindingService bindings;
    private WorkerDeliveryCodec codec;
    private ControlCallService service;

    @BeforeEach
    void setUp() {
        catalog = mock(WorkerResourceCatalog.class);
        scores = mock(WorkerScoreCore.class);
        bindings = mock(WorkerBindingService.class);
        codec = new WorkerDeliveryCodec();
        ControlCallProperties properties = new ControlCallProperties(
                3_000,
                10_000,
                1_000,
                10_000
        );
        service = new ControlCallService(
                catalog,
                scores,
                bindings,
                endpoints(),
                new ControlCallRegistry(properties),
                properties
        );
    }

    @Test
    void adapterScopedBatchUsesOneReadPerOwnerAndRejectsOtherBindings() {
        List<String> workerIds = List.of(
                "worker-ok",
                "worker-missing",
                "worker-score-missing",
                "worker-running",
                "worker-unbound",
                "worker-other-adapter"
        );
        LinkedHashMap<String, WorkerDescriptor> workers = new LinkedHashMap<>();
        LinkedHashMap<String, WorkerScoreState> scoreStates =
                new LinkedHashMap<>();
        LinkedHashMap<String, String> endpointIds = new LinkedHashMap<>();
        for (String workerId : workerIds) {
            workers.put(workerId, descriptor(workerId));
            scoreStates.put(workerId, paused(workerId));
            endpointIds.put(workerId, ADAPTER_ID);
        }
        workers.put("worker-missing", null);
        scoreStates.put("worker-score-missing", null);
        scoreStates.put("worker-running", running("worker-running"));
        endpointIds.put("worker-unbound", null);
        endpointIds.put("worker-other-adapter", OTHER_ADAPTER_ID);
        when(catalog.getWorkerDescriptors(GROUP_ID, workerIds))
                .thenReturn(workers);
        when(scores.getScoreStates(GROUP_ID, workerIds))
                .thenReturn(scoreStates);
        when(bindings.currentEndpointManagerIds(workerIds))
                .thenReturn(endpointIds);

        DeferredResult<ResponseEntity<ControlBatchCallResponse>> deferred =
                service.call(ADAPTER_ID, workerRequest(workerIds));
        DeliveryCommand command = service.consume(
                ADAPTER_ID,
                100
        ).get("worker-ok");
        assertThat(command).isNotNull();
        assertThat(command.messageType()).isEqualTo("device.custom-event");
        assertThat(command.payload()).isEqualTo(
                "{\"workerId\":\"worker-ok\"}"
        );
        assertThat(service.consume(OTHER_ADAPTER_ID, 100)).isEmpty();
        service.completeReports(
                ADAPTER_ID,
                List.of(workerReport(
                        "worker-ok",
                        command.forward(),
                        "3302"
                ))
        );

        ControlBatchCallResponse response = response(deferred).getBody();
        assertThat(response).isNotNull();
        assertThat(response.status().wireValue()).isEqualTo("partial");
        assertThat(response.results().keySet()).containsExactlyElementsOf(
                workerIds
        );
        assertThat(response.results().get("worker-ok").status())
                .isEqualTo(ControlTargetStatus.OBSERVED);
        assertThat(response.results().get("worker-ok").outcomeCode())
                .isEqualTo("3302");
        assertReason(response, "worker-missing", ControlTargetReason.NOT_FOUND);
        assertReason(
                response,
                "worker-score-missing",
                ControlTargetReason.SCORE_UNAVAILABLE
        );
        assertReason(
                response,
                "worker-running",
                ControlTargetReason.CONTROL_ONLY_REQUIRED
        );
        assertReason(response, "worker-unbound", ControlTargetReason.NOT_BOUND);
        assertReason(
                response,
                "worker-other-adapter",
                ControlTargetReason.ENDPOINT_MISMATCH
        );
        verify(catalog).getWorkerDescriptors(GROUP_ID, workerIds);
        verify(scores).getScoreStates(GROUP_ID, workerIds);
        verify(bindings).currentEndpointManagerIds(workerIds);
        verifyNoMoreInteractions(scores);
    }

    @Test
    void oneHundredRejectedWorkersStillUseOneBoundedReadPerOwner() {
        List<String> workerIds = IntStream.range(0, 100)
                .mapToObj(index -> "worker-" + index)
                .toList();
        LinkedHashMap<String, WorkerDescriptor> workers = new LinkedHashMap<>();
        LinkedHashMap<String, WorkerScoreState> scoreStates =
                new LinkedHashMap<>();
        LinkedHashMap<String, String> endpointIds = new LinkedHashMap<>();
        workerIds.forEach(workerId -> {
            workers.put(workerId, null);
            scoreStates.put(workerId, null);
            endpointIds.put(workerId, null);
        });
        when(catalog.getWorkerDescriptors(GROUP_ID, workerIds))
                .thenReturn(workers);
        when(scores.getScoreStates(GROUP_ID, workerIds))
                .thenReturn(scoreStates);
        when(bindings.currentEndpointManagerIds(workerIds))
                .thenReturn(endpointIds);

        var deferred = service.call(ADAPTER_ID, workerRequest(workerIds));

        assertThat(response(deferred).getBody().results()).hasSize(100);
        verify(catalog).getWorkerDescriptors(GROUP_ID, workerIds);
        verify(scores).getScoreStates(GROUP_ID, workerIds);
        verify(bindings).currentEndpointManagerIds(workerIds);
    }

    @Test
    void oneHundredEligibleWorkersCompleteAsOneObservedBatch() {
        List<String> workerIds = IntStream.range(0, 100)
                .mapToObj(index -> "worker-" + index)
                .toList();
        LinkedHashMap<String, WorkerDescriptor> workers = new LinkedHashMap<>();
        LinkedHashMap<String, WorkerScoreState> scoreStates =
                new LinkedHashMap<>();
        LinkedHashMap<String, String> endpointIds = new LinkedHashMap<>();
        workerIds.forEach(workerId -> {
            workers.put(workerId, descriptor(workerId));
            scoreStates.put(workerId, paused(workerId));
            endpointIds.put(workerId, ADAPTER_ID);
        });
        when(catalog.getWorkerDescriptors(GROUP_ID, workerIds))
                .thenReturn(workers);
        when(scores.getScoreStates(GROUP_ID, workerIds))
                .thenReturn(scoreStates);
        when(bindings.currentEndpointManagerIds(workerIds))
                .thenReturn(endpointIds);

        var deferred = service.call(ADAPTER_ID, workerRequest(workerIds));
        Map<String, DeliveryCommand> commands = service.consume(
                ADAPTER_ID,
                100
        );
        List<String> reports = workerIds.stream()
                .map(workerId -> codec.encodeDeliveryReport(workerReport(
                        workerId,
                        commands.get(workerId).forward(),
                        "200"
                )))
                .toList();
        service.completeReports(
                ADAPTER_ID,
                reports.stream().map(codec::decodeDeliveryReport).toList()
        );

        ControlBatchCallResponse response = response(deferred).getBody();
        assertThat(response).isNotNull();
        assertThat(response.status().wireValue()).isEqualTo("observed");
        assertThat(response.results()).hasSize(100);
        assertThat(response.results().keySet())
                .containsExactlyElementsOf(workerIds);
        assertThat(response.results().values())
                .allMatch(result -> result.status()
                        == ControlTargetStatus.OBSERVED);
        verify(catalog).getWorkerDescriptors(GROUP_ID, workerIds);
        verify(scores).getScoreStates(GROUP_ID, workerIds);
        verify(bindings).currentEndpointManagerIds(workerIds);
        verifyNoMoreInteractions(scores);
    }

    @Test
    void invalidWorkerShapesFailBeforeAnyOwnerRead() {
        assertInvalid(new ControlCallRequest(
                GROUP_ID,
                Map.of(),
                "event",
                null,
                3_000L
        ));
        assertInvalid(new ControlCallRequest(
                GROUP_ID,
                null,
                "event",
                null,
                3_000L
        ));
        assertInvalid(new ControlCallRequest(
                null,
                Map.of("worker-1", "{}"),
                "event",
                null,
                3_000L
        ));
        assertInvalid(new ControlCallRequest(
                GROUP_ID,
                Map.of("worker-1", "{}"),
                "event",
                "adapter-payload",
                3_000L
        ));
        LinkedHashMap<String, String> nullPayload = new LinkedHashMap<>();
        nullPayload.put("worker-1", null);
        assertInvalid(new ControlCallRequest(
                GROUP_ID,
                nullPayload,
                "event",
                null,
                3_000L
        ));
        verifyNoInteractions(catalog, scores, bindings);
    }

    @Test
    void ownerReadFailureRejectsTheWholeBatchAsUnavailable() {
        List<String> workerIds = List.of("worker-1", "worker-2");
        when(catalog.getWorkerDescriptors(GROUP_ID, workerIds))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertThatThrownBy(() -> service.call(
                ADAPTER_ID,
                workerRequest(workerIds)
        )).isInstanceOfSatisfying(ServerException.class, error ->
                assertThat(error.errorCode()).isEqualTo(
                        ServerErrorCode.CONTROL_CALL_UNAVAILABLE
                ));
    }

    @Test
    void arbitraryAdapterEventPassesThroughWithoutWorkerOwners() {
        DeferredResult<ResponseEntity<ControlBatchCallResponse>> deferred =
                service.call(
                        ADAPTER_ID,
                        new ControlCallRequest(
                                null,
                                null,
                                "adapter.vendor.inspect",
                                "{\"detail\":true}",
                                3_000L
                        )
                );
        DeliveryCommand command = service.consume(
                ADAPTER_ID,
                100
        ).get(ControlCallRegistry.ADAPTER_TARGET_ADDRESS);
        assertThat(command.messageType()).isEqualTo(
                "adapter.vendor.inspect"
        );
        assertThat(command.payload()).isEqualTo("{\"detail\":true}");
        DeliveryReport report = DeliveryReport.create(
                DeliveryEndpoint.ADAPTER,
                ADAPTER_ID,
                DeliveryEndpoint.SYSTEM,
                "adapter.vendor.inspect",
                "23005",
                "unsupported",
                command.forward()
        );
        service.completeReports(ADAPTER_ID, List.of(report));

        ControlBatchCallResponse response = response(deferred).getBody();
        assertThat(response.status().wireValue()).isEqualTo("observed");
        assertThat(response.results()).containsOnlyKeys(ADAPTER_ID);
        assertThat(response.results().get(ADAPTER_ID).status())
                .isEqualTo(ControlTargetStatus.OBSERVED);
        assertThat(response.results().get(ADAPTER_ID).outcomeCode())
                .isEqualTo("23005");
        verifyNoInteractions(catalog, scores, bindings);
    }

    private void assertInvalid(ControlCallRequest request) {
        assertThatThrownBy(() -> service.call(ADAPTER_ID, request))
                .isInstanceOfSatisfying(ServerException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(
                                ServerErrorCode.INVALID_CONTROL_CALL_REQUEST
                        ));
    }

    private static ControlCallRequest workerRequest(List<String> workerIds) {
        LinkedHashMap<String, String> workerPayloads = new LinkedHashMap<>();
        workerIds.forEach(workerId -> workerPayloads.put(
                workerId,
                "{\"workerId\":\"" + workerId + "\"}"
        ));
        return new ControlCallRequest(
                GROUP_ID,
                workerPayloads,
                "device.custom-event",
                null,
                3_000L
        );
    }

    private static WorkerDescriptor descriptor(String workerId) {
        return new WorkerDescriptor(
                workerId,
                GROUP_ID,
                ADAPTER_ID,
                Map.of(),
                Map.of()
        );
    }

    private static WorkerScoreState paused(String workerId) {
        return new WorkerScoreState(
                workerId,
                1,
                WorkerScorePolarity.HOT_ACQUIRE,
                WorkerScoreCore.PAUSE_TIME_MILLIS,
                0,
                0
        );
    }

    private static WorkerScoreState running(String workerId) {
        return new WorkerScoreState(
                workerId,
                1,
                WorkerScorePolarity.HOT_ACQUIRE,
                1_000,
                0,
                0
        );
    }

    private static DeliveryReport workerReport(
            String workerId,
            String forward,
            String outcomeCode
    ) {
        return DeliveryReport.create(
                DeliveryEndpoint.WORKER,
                workerId,
                DeliveryEndpoint.SYSTEM,
                "device.custom-event",
                outcomeCode,
                "{\"battery\":87}",
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

    @SuppressWarnings("unchecked")
    private static ResponseEntity<ControlBatchCallResponse> response(
            DeferredResult<ResponseEntity<ControlBatchCallResponse>> deferred
    ) {
        return (ResponseEntity<ControlBatchCallResponse>) deferred.getResult();
    }

    private static void assertReason(
            ControlBatchCallResponse response,
            String workerId,
            ControlTargetReason reason
    ) {
        assertThat(response.results().get(workerId).status())
                .isEqualTo(ControlTargetStatus.REJECTED);
        assertThat(response.results().get(workerId).reason())
                .isEqualTo(reason);
    }
}
