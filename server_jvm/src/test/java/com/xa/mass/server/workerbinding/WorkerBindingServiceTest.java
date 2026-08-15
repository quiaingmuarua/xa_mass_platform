package com.xa.mass.server.workerbinding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.worker.WorkerRuntime;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDeclaration;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeStatus;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.workerbinding.WorkerBindingProperties.EndpointProperties;
import com.xa.mass.server.workeridentity.WorkerIdentityService;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkerBindingServiceTest {

    private static final String WORKER_ID =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";
    private WorkerBindingRegistry registry;
    private WorkerIdentityService identities;
    private WorkerRuntime workerRuntime;
    private WorkerBindingService service;

    @BeforeEach
    void setUp() {
        registry = mock(WorkerBindingRegistry.class);
        identities = mock(WorkerIdentityService.class);
        workerRuntime = mock(WorkerRuntime.class);
        service = new WorkerBindingService(
                registry,
                endpointDirectory(),
                identities,
                workerRuntime
        );
        when(workerRuntime.upsertWorker(any())).thenReturn(result(
                WorkerRuntimeStatus.OK
        ));
    }

    @Test
    void firstBindPersistsSelectedEndpointAndUpsertsKernelProjection() {
        when(registry.getEndpointManagerId(WORKER_ID)).thenReturn(null);
        when(registry.bindIfAbsent(WORKER_ID, "websocket-a"))
                .thenReturn("websocket-a");

        WorkerEndpointBinding binding = service.bind(
                "group-1",
                WORKER_ID,
                WorkerTransportType.WEBSOCKET,
                properties("local")
        );

        assertThat(binding).isEqualTo(new WorkerEndpointBinding(
                "websocket-a",
                WorkerTransportType.WEBSOCKET,
                URI.create("ws://127.0.0.1:18083/worker")
        ));
        verify(identities).requireRegistration(
                "group-1",
                properties("local"),
                WORKER_ID
        );
        verify(registry).bindIfAbsent(WORKER_ID, "websocket-a");
        ArgumentCaptor<WorkerDeclaration> declaration =
                ArgumentCaptor.forClass(WorkerDeclaration.class);
        verify(workerRuntime).upsertWorker(declaration.capture());
        assertThat(declaration.getValue()).isEqualTo(new WorkerDeclaration(
                WORKER_ID,
                "group-1",
                "websocket-a",
                properties("local")
        ));
    }

    @Test
    void repeatBindReusesEndpointAndRefreshesWorkerProperties() {
        when(registry.getEndpointManagerId(WORKER_ID))
                .thenReturn("websocket-a");

        WorkerEndpointBinding binding = service.bind(
                "group-1",
                WORKER_ID,
                WorkerTransportType.WEBSOCKET,
                Map.of(
                        "clientWorkerKey",
                        "installation-1",
                        "version",
                        2
                )
        );

        assertThat(binding.endpointManagerId()).isEqualTo("websocket-a");
        verify(registry, never()).bindIfAbsent(any(), any());
        ArgumentCaptor<WorkerDeclaration> declaration =
                ArgumentCaptor.forClass(WorkerDeclaration.class);
        verify(workerRuntime).upsertWorker(declaration.capture());
        assertThat(declaration.getValue().workerProperties())
                .isEqualTo(Map.of(
                        "clientWorkerKey",
                        "installation-1",
                        "version",
                        2
                ));
    }

    @Test
    void existingBindingCannotChangeTransportOrDisappearFromDirectory() {
        when(registry.getEndpointManagerId(WORKER_ID))
                .thenReturn("socket-a", "removed-endpoint");

        assertFailure(
                () -> bindWebSocket(Map.of()),
                ServerErrorCode.WORKER_BINDING_CONFLICT
        );
        assertFailure(
                () -> bindWebSocket(Map.of()),
                ServerErrorCode.WORKER_ENDPOINT_UNAVAILABLE
        );
        verifyNoInteractions(workerRuntime);
    }

    @Test
    void concurrentFirstBindHonorsThePersistedWinner() {
        when(registry.getEndpointManagerId(WORKER_ID)).thenReturn(null);
        when(registry.bindIfAbsent(WORKER_ID, "websocket-a"))
                .thenReturn("socket-a");

        assertFailure(
                () -> bindWebSocket(Map.of()),
                ServerErrorCode.WORKER_BINDING_CONFLICT
        );
        verifyNoInteractions(workerRuntime);
    }

    @Test
    void kernelFailureKeepsBindingForRetry() {
        when(registry.getEndpointManagerId(WORKER_ID))
                .thenReturn(null, "websocket-a");
        when(registry.bindIfAbsent(WORKER_ID, "websocket-a"))
                .thenReturn("websocket-a");
        when(workerRuntime.upsertWorker(any())).thenReturn(
                result(WorkerRuntimeStatus.REJECTED),
                result(WorkerRuntimeStatus.OK)
        );

        assertFailure(
                () -> bindWebSocket(Map.of("attempt", 1)),
                ServerErrorCode.WORKER_BINDING_UNAVAILABLE
        );
        assertThat(bindWebSocket(Map.of("attempt", 2)).endpointManagerId())
                .isEqualTo("websocket-a");
        verify(registry, times(1)).bindIfAbsent(
                WORKER_ID,
                "websocket-a"
        );
        verify(workerRuntime, times(2)).upsertWorker(any());
    }

    @Test
    void endpointVerificationDistinguishesMissingAndWrongBindings() {
        when(registry.getEndpointManagerId(WORKER_ID))
                .thenReturn(null, "socket-a", "websocket-a");

        assertFailure(
                () -> service.requireCurrentEndpoint("websocket-a", WORKER_ID),
                ServerErrorCode.WORKER_BINDING_NOT_FOUND
        );
        assertFailure(
                () -> service.requireCurrentEndpoint("websocket-a", WORKER_ID),
                ServerErrorCode.WORKER_BINDING_CONFLICT
        );
        service.requireCurrentEndpoint("websocket-a", WORKER_ID);
    }

    @Test
    void currentEndpointManagerIdsUsesOneBoundedOwnerRead() {
        String second = "e54a0f75-a8f3-4b08-9aa0-22fc42ca3ea2";
        when(registry.getEndpointManagerIds(List.of(
                WORKER_ID,
                second
        ))).thenReturn(linkedBindings(
                WORKER_ID, "websocket-a",
                second, null
        ));

        assertThat(service.currentEndpointManagerIds(List.of(
                WORKER_ID,
                second
        ))).containsExactly(
                entry(WORKER_ID, "websocket-a"),
                entry(second, null)
        );
        verify(registry).getEndpointManagerIds(List.of(
                WORKER_ID,
                second
        ));
    }

    private WorkerEndpointBinding bindWebSocket(
            Map<String, Object> workerProperties
    ) {
        return service.bind(
                "group-1",
                WORKER_ID,
                WorkerTransportType.WEBSOCKET,
                withClientKey(workerProperties)
        );
    }

    private static Map<String, Object> properties(String region) {
        return Map.of(
                "clientWorkerKey",
                "installation-1",
                "region",
                region
        );
    }

    private static Map<String, Object> withClientKey(
            Map<String, Object> properties
    ) {
        LinkedHashMap<String, Object> complete = new LinkedHashMap<>();
        complete.put("clientWorkerKey", "installation-1");
        complete.putAll(properties);
        return complete;
    }

    private static WorkerEndpointDirectory endpointDirectory() {
        LinkedHashMap<String, EndpointProperties> endpoints =
                new LinkedHashMap<>();
        endpoints.put("socket-a", endpoint(
                WorkerTransportType.SOCKET,
                "tcp://127.0.0.1:18085"
        ));
        endpoints.put("websocket-a", endpoint(
                WorkerTransportType.WEBSOCKET,
                "ws://127.0.0.1:18083/worker"
        ));
        return new WorkerEndpointDirectory(endpoints);
    }

    private static EndpointProperties endpoint(
            WorkerTransportType type,
            String uri
    ) {
        return new EndpointProperties(type, URI.create(uri));
    }

    private static WorkerRuntimeResult result(WorkerRuntimeStatus status) {
        return new WorkerRuntimeResult(status, status.wireValue());
    }

    private static Map<String, String> linkedBindings(
            String firstId,
            String firstEndpoint,
            String secondId,
            String secondEndpoint
    ) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        result.put(firstId, firstEndpoint);
        result.put(secondId, secondEndpoint);
        return result;
    }

    private static void assertFailure(
            Runnable action,
            ServerErrorCode expectedCode
    ) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ServerException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(expectedCode)
                );
    }
}
