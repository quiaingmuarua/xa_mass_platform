package com.xa.mass.server.worker.connection;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerDescriptor;
import com.xa.mass.server.error.*;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.worker.endpoint.*;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterManager;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class WorkerConnectionControlServiceTest {
    private final WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
    private final WorkerEndpointDirectory endpoints = mock(WorkerEndpointDirectory.class);
    private final WorkerDeliveryAdapterManager adapters = mock(WorkerDeliveryAdapterManager.class);
    private final WorkerConnectionControlStore store = mock(WorkerConnectionControlStore.class);
    private final TaskCaller admin = mock(TaskCaller.class);
    private final WorkerConnectionControlService service = new WorkerConnectionControlService(catalog, endpoints, adapters, store,
            new OperationGuard(), Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC));
    private final WorkerConnectionControlStore.Change change = new WorkerConnectionControlStore.Change("request", null, 1000L, "maintenance");
    private final WorkerConnectionControlStore.Control cold = new WorkerConnectionControlStore.Control("r", "request", null, 1000L, "maintenance", "admin", 100);
    @BeforeEach void setup() {
        when(admin.userId()).thenReturn("admin");
        when(catalog.getWorkerDescriptors(List.of("w"))).thenReturn(Map.of("w", new WorkerDescriptor("w", "g", "adapter")));
        when(endpoints.find("adapter")).thenReturn(new WorkerEndpointDirectory.Endpoint(WorkerTransportType.WEBSOCKET, URI.create("ws://localhost/worker")));
        when(store.update("g", "w", change, "admin", 100)).thenReturn(cold);
    }
    @Test void administratorAdmissionPrecedesAllOwnerAccess() {
        doThrow(new ServerException(ServerErrorCode.TASK_ACCESS_DENIED, "test.requireAdmin", null, null)).when(admin).requireAdmin();
        assertCode(() -> service.put(admin, "g", "w", change), ServerErrorCode.TASK_ACCESS_DENIED);
        assertCode(() -> service.get(admin, "g", "w"), ServerErrorCode.TASK_ACCESS_DENIED);
        verifyNoInteractions(catalog, store, adapters, endpoints);
    }
    @Test void validatesManagedAdapterThenPersistsThenRevokes() {
        assertThat(service.put(admin, "g", "w", change).status()).isEqualTo("cooldown");
        var order = inOrder(adapters, store);
        order.verify(adapters).requireConnectionControl("adapter");
        order.verify(store).update("g", "w", change, "admin", 100);
        order.verify(adapters).revokeWorkerConnection("adapter", "w", 1000);
    }
    @Test void failedRevocationPreservesDecisionAndExactRetryCanRevokeAgain() {
        doThrow(new IllegalStateException("close submission failed")).doNothing().when(adapters).revokeWorkerConnection("adapter", "w", 1000);
        assertCode(() -> service.put(admin, "g", "w", change), ServerErrorCode.WORKER_CONNECTION_CONTROL_UNAVAILABLE);
        when(store.read("g", "w")).thenReturn(cold);
        assertThat(service.get(admin, "g", "w").requestId()).isEqualTo("request");
        assertThat(service.put(admin, "g", "w", change).revision()).isEqualTo("r");
        verify(adapters, times(2)).revokeWorkerConnection("adapter", "w", 1000);
    }
    @Test void unsupportedTransportAndMissingAdapterNeverWrite() {
        when(endpoints.find("adapter")).thenReturn(new WorkerEndpointDirectory.Endpoint(WorkerTransportType.POLLING, URI.create("http://localhost")));
        assertThat(service.get(admin, "g", "w").supported()).isFalse();
        assertCode(() -> service.put(admin, "g", "w", change), ServerErrorCode.INVALID_WORKER_CONNECTION_CONTROL);
        verify(store, never()).update(any(), any(), any(), any(), anyLong());
    }
    @Test void releaseDoesNotDisconnectOrTouchScheduling() {
        var release = new WorkerConnectionControlStore.Change("release", "r", null, "ready");
        when(store.update("g", "w", release, "admin", 100)).thenReturn(new WorkerConnectionControlStore.Control("r2", "release", "r", null, "ready", "admin", 100));
        assertThat(service.put(admin, "g", "w", release).status()).isEqualTo("allowed");
        verify(adapters, never()).revokeWorkerConnection(any(), any(), anyLong());
        verify(catalog).getWorkerDescriptors(List.of("w"));
        verifyNoMoreInteractions(catalog);
    }
    @Test void stateConflictAndReadFailureStayDistinct() {
        when(store.update("g", "w", change, "admin", 100)).thenThrow(new WorkerConnectionControlStore.Conflict());
        assertCode(() -> service.put(admin, "g", "w", change), ServerErrorCode.WORKER_CONNECTION_CONTROL_CONFLICT);
        when(store.read("g", "w")).thenThrow(new IllegalStateException("offline"));
        assertCode(() -> service.get(admin, "g", "w"), ServerErrorCode.WORKER_CONNECTION_CONTROL_UNAVAILABLE);
        verify(adapters, never()).revokeWorkerConnection(any(), any(), anyLong());
    }
    private static void assertCode(Runnable action, ServerErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ServerException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }
}
