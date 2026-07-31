package com.xa.mass.server.workerassembly.phonenumber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDeclaration;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeStatus;
import com.xa.mass.server.workerassembly.ServerWorkerAssemblyProperties
        .BundleProperties;
import com.xa.mass.server.workerassembly.ServerWorkerAssemblyProperties
        .BundleType;
import com.xa.mass.server.workerassembly.WorkerAssemblyException;
import com.xa.mass.worker.transport.websocket.WebSocketWorkerTransport;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class PhoneNumberWorkerBundleTest {

    @Test
    void upsertsOwnerResourcesThenStartsDeterministicWorkers() {
        WorkerResourceCatalog catalog = mock(
                WorkerResourceCatalog.class
        );
        WorkerRuntime runtime = mock(WorkerRuntime.class);
        when(catalog.upsertWorkerGroup(any())).thenReturn(
                accepted(WorkerRuntimeStatus.OK)
        );
        when(runtime.upsertWorker(any())).thenReturn(
                accepted(WorkerRuntimeStatus.NOOP)
        );
        WebSocketWorkerTransport first = connectedWorker();
        WebSocketWorkerTransport second = connectedWorker();
        PhoneNumberWorkerBundle bundle = new PhoneNumberWorkerBundle(
                "phone-number",
                properties(2, Duration.ofSeconds(1)),
                workerUri(),
                catalog,
                runtime,
                (workerId, ignored, uri) -> workerId.endsWith("001")
                        ? first
                        : second
        );

        bundle.start();
        bundle.start();

        ArgumentCaptor<WorkerGroupDescriptor> group =
                ArgumentCaptor.forClass(WorkerGroupDescriptor.class);
        verify(catalog).upsertWorkerGroup(group.capture());
        assertThat(group.getValue().workerGroupId())
                .isEqualTo("phonenumber-workers");
        assertThat(group.getValue().eventCodes())
                .containsExactly(PhoneNumberCapability.EVENT_CODE);
        assertThat(group.getValue().itemAllocationFields())
                .containsExactly("workerId");

        ArgumentCaptor<WorkerDeclaration> workers =
                ArgumentCaptor.forClass(WorkerDeclaration.class);
        verify(runtime, times(2)).upsertWorker(workers.capture());
        assertThat(workers.getAllValues())
                .extracting(WorkerDeclaration::workerId)
                .containsExactly(
                        "phonenumber-worker-001",
                        "phonenumber-worker-002"
                );
        assertThat(workers.getAllValues())
                .allSatisfy(declaration ->
                        assertThat(declaration.endpointManagerId())
                                .isEqualTo("websocket-1")
                );
        verify(first).start();
        verify(second).start();

        bundle.close();
        bundle.close();

        InOrder closeOrder = inOrder(first, second);
        closeOrder.verify(second).close();
        closeOrder.verify(first).close();
    }

    @Test
    void rejectedOwnerResultStopsBeforeWorkerCreation() {
        WorkerResourceCatalog catalog = mock(
                WorkerResourceCatalog.class
        );
        WorkerRuntime runtime = mock(WorkerRuntime.class);
        when(catalog.upsertWorkerGroup(any())).thenReturn(
                new WorkerRuntimeResult(
                        WorkerRuntimeStatus.INVALID,
                        "invalid group"
                )
        );
        PhoneNumberWorkerBundle.WorkerFactory factory = mock(
                PhoneNumberWorkerBundle.WorkerFactory.class
        );
        PhoneNumberWorkerBundle bundle = new PhoneNumberWorkerBundle(
                "phone-number",
                properties(1, Duration.ofSeconds(1)),
                workerUri(),
                catalog,
                runtime,
                factory
        );

        assertThatThrownBy(bundle::start)
                .isInstanceOf(WorkerAssemblyException.class)
                .extracting("errorCode")
                .isEqualTo(14002);

        verify(factory, times(0)).create(any(), any(), any());
    }

    @Test
    void partialTransportStartFailureClosesAllCreatedWorkers() {
        WorkerResourceCatalog catalog = acceptedCatalog();
        WorkerRuntime runtime = acceptedRuntime();
        WebSocketWorkerTransport first = connectedWorker();
        WebSocketWorkerTransport second = connectedWorker();
        doThrow(new IllegalStateException("start failed"))
                .when(second)
                .start();
        PhoneNumberWorkerBundle bundle = new PhoneNumberWorkerBundle(
                "phone-number",
                properties(2, Duration.ofSeconds(1)),
                workerUri(),
                catalog,
                runtime,
                (workerId, ignored, uri) -> workerId.endsWith("001")
                        ? first
                        : second
        );

        assertThatThrownBy(bundle::start)
                .isInstanceOf(WorkerAssemblyException.class)
                .extracting("errorCode")
                .isEqualTo(14004);

        InOrder closeOrder = inOrder(first, second);
        closeOrder.verify(second).close();
        closeOrder.verify(first).close();
    }

    @Test
    void connectionTimeoutFailsStartupAndClosesWorker() {
        WebSocketWorkerTransport worker = mock(
                WebSocketWorkerTransport.class
        );
        PhoneNumberWorkerBundle bundle = new PhoneNumberWorkerBundle(
                "phone-number",
                properties(1, Duration.ofMillis(1)),
                workerUri(),
                acceptedCatalog(),
                acceptedRuntime(),
                (workerId, ignored, uri) -> worker
        );

        assertThatThrownBy(bundle::start)
                .isInstanceOf(WorkerAssemblyException.class)
                .extracting("errorCode")
                .isEqualTo(14005);

        verify(worker).close();
    }

    private static WorkerResourceCatalog acceptedCatalog() {
        WorkerResourceCatalog catalog = mock(
                WorkerResourceCatalog.class
        );
        when(catalog.upsertWorkerGroup(any())).thenReturn(
                accepted(WorkerRuntimeStatus.OK)
        );
        return catalog;
    }

    private static WorkerRuntime acceptedRuntime() {
        WorkerRuntime runtime = mock(WorkerRuntime.class);
        when(runtime.upsertWorker(any())).thenReturn(
                accepted(WorkerRuntimeStatus.OK)
        );
        return runtime;
    }

    private static WebSocketWorkerTransport connectedWorker() {
        WebSocketWorkerTransport worker = mock(
                WebSocketWorkerTransport.class
        );
        when(worker.isConnected()).thenReturn(true);
        return worker;
    }

    private static WorkerRuntimeResult accepted(
            WorkerRuntimeStatus status
    ) {
        return new WorkerRuntimeResult(status);
    }

    private static BundleProperties properties(
            int workerCount,
            Duration connectTimeout
    ) {
        return new BundleProperties(
                BundleType.PHONE_NUMBER,
                "websocket-1",
                "phonenumber-workers",
                "phonenumber-worker-",
                workerCount,
                Duration.ofSeconds(10),
                Duration.ofMillis(250),
                connectTimeout
        );
    }

    private static URI workerUri() {
        return URI.create(
                "ws://127.0.0.1:18083"
                        + "/api/v1/worker-delivery/websocket"
        );
    }
}
