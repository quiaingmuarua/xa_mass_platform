package com.xa.mass.server.worker.preparation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.*;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.worker.endpoint.*;
import com.xa.mass.server.worker.endpoint.WorkerEndpointDirectory.Endpoint;
import com.xa.mass.server.worker.identity.*;
import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerPreparationServiceTest {
    private final WorkerIdentityService identities = mock(WorkerIdentityService.class);
    private final WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
    private final WorkerEndpointDirectory endpoints = mock(WorkerEndpointDirectory.class);
    private final WorkerPreparationService service = new WorkerPreparationService(identities, endpoints, catalog);
    private final Endpoint endpoint = new Endpoint( WorkerTransportType.WEBSOCKET,
            URI.create("ws://127.0.0.1:18083/connect"));

    @BeforeEach
    void setup() {
        when(identities.registrationKey(eq(WorkerRegistrationKind.CLIENT_KEY), anyMap()))
                .thenAnswer(call -> ((Map<?, ?>) call.getArgument(1)).get("clientWorkerKey"));
        when(catalog.getWorkerGroupDescriptors(List.of("group"))).thenReturn(Map.of(
                "group", new WorkerGroupDescriptor("group", Map.of(), Set.of())));
        when(endpoints.defaultEndpointId(WorkerTransportType.WEBSOCKET)).thenReturn("default");
        when(endpoints.find("default")).thenReturn(endpoint);
        when(identities.registerAll(eq("group"), anyList())).thenAnswer(call -> call.getArgument(1));
        when(catalog.registerWorkers(eq("group"), anyList(), eq("default"))).thenAnswer(call -> {
            Map<String, WorkerRegistrationResult> result = new LinkedHashMap<>();
            ((List<String>) call.getArgument(1)).forEach(id -> result.put(id,
                    new WorkerRegistrationResult(RegistrationStatus.OK, "default", null)));
            return result;
        });
    }

    private List<WorkerPreparationService.PreparedWorker> prepare(List<Map<String, Object>> properties) {
        return service.prepareAll("group", WorkerRegistrationKind.CLIENT_KEY, WorkerTransportType.WEBSOCKET, properties);
    }

    @Test
    void validatesThenReadsGroupAndDefaultBeforeOneIdentityAndCatalogBatch() {
        var properties = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> Map.<String, Object>of("clientWorkerKey", "worker-" + i)).toList();
        var ids = java.util.stream.IntStream.range(0, 100).mapToObj(i -> "worker-" + i).toList();
        assertThat(prepare(properties)).extracting(WorkerPreparationService.PreparedWorker::workerId)
                .containsExactlyElementsOf(ids);
        var order = inOrder(catalog, endpoints, identities);
        order.verify(catalog).getWorkerGroupDescriptors(List.of("group"));
        order.verify(endpoints).defaultEndpointId(WorkerTransportType.WEBSOCKET);
        order.verify(identities).registerAll("group", ids);
        order.verify(catalog).registerWorkers("group", ids, "default");
    }

    @Test
    void actualEndpointWinsOverNewDefaultAndMustMatchRequestedType() {
        when(catalog.registerWorkers("group", List.of("w"), "default"))
                .thenReturn(Map.of("w", new WorkerRegistrationResult(RegistrationStatus.NOOP, "actual", null)));
        when(endpoints.find("actual")).thenReturn(new Endpoint( WorkerTransportType.WEBSOCKET,
                URI.create("ws://127.0.0.1:19000/actual")));
        assertThat(prepare(List.of(Map.of("clientWorkerKey", "w"))).getFirst().endpointUri())
                .isEqualTo(URI.create("ws://127.0.0.1:19000/actual"));
        when(endpoints.find("actual")).thenReturn(new Endpoint( WorkerTransportType.SOCKET,
                URI.create("tcp://127.0.0.1:19000")));
        assertCode(() -> prepare(List.of(Map.of("clientWorkerKey", "w"))), ServerErrorCode.WORKER_BINDING_CONFLICT);
    }

    @Test
    void missingGroupOrDefaultCreatesNoIdentityRecords() {
        when(catalog.getWorkerGroupDescriptors(List.of("group"))).thenReturn(Map.of());
        assertCode(() -> prepare(List.of(Map.of("clientWorkerKey", "w"))), ServerErrorCode.WORKER_GROUP_NOT_FOUND);
        verify(identities, never()).registerAll(any(), any());
        when(catalog.getWorkerGroupDescriptors(List.of("group"))).thenReturn(Map.of(
                "group", new WorkerGroupDescriptor("group", Map.of(), Set.of())));
        when(endpoints.defaultEndpointId(WorkerTransportType.WEBSOCKET)).thenReturn(null);
        assertCode(() -> prepare(List.of(Map.of("clientWorkerKey", "w"))), ServerErrorCode.WORKER_ENDPOINT_UNAVAILABLE);
        verify(identities, never()).registerAll(any(), any());
        verify(catalog, never()).registerWorkers(any(), any(), any());
    }

    @Test
    void invalidWholeBatchHasNoResourceSideEffects() {
        var duplicate = Map.<String,Object>of("clientWorkerKey", "same");
        assertCode(() -> prepare(List.of(duplicate, duplicate)), ServerErrorCode.INVALID_WORKER_IDENTITY_REQUEST);
        verifyNoInteractions(catalog, endpoints);
        verify(identities, never()).registerAll(any(), any());
    }

    @Test
    void unknownRegistrationCompletionIsRetryableAndRetainsIdentity() {
        when(catalog.registerWorkers("group", List.of("w"), "default"))
                .thenThrow(new IllegalStateException("unknown completion"))
                .thenReturn(Map.of("w", new WorkerRegistrationResult(RegistrationStatus.NOOP, "default", null)));
        assertCode(() -> prepare(List.of(Map.of("clientWorkerKey", "w"))), ServerErrorCode.WORKER_BINDING_UNAVAILABLE);
        assertThat(prepare(List.of(Map.of("clientWorkerKey", "w"))).getFirst().workerId()).isEqualTo("w");
        verify(identities, times(2)).registerAll("group", List.of("w"));
    }

    private static void assertCode(Runnable action, ServerErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ServerException.class,
                error -> assertThat(error.errorCode()).isEqualTo(code));
    }
}
