package com.xa.mass.scenarioworkers;

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
import com.xa.mass.kernel.worker.WorkerPropertyIndexRuntime;
import com.xa.mass.kernel.worker.WorkerRuntime;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDeclaration;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeStatus;
import com.xa.mass.worker.transport.websocket.WebSocketWorkerTransport;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
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
        WorkerPropertyIndexRuntime propertyIndex = acceptedIndex();
        when(catalog.upsertWorkerGroup(any())).thenReturn(
                accepted(WorkerRuntimeStatus.OK)
        );
        when(runtime.upsertWorker(any())).thenReturn(
                accepted(WorkerRuntimeStatus.NOOP)
        );
        WebSocketWorkerTransport first = connectedWorker();
        WebSocketWorkerTransport second = connectedWorker();
        PhoneNumberWorkerBundle bundle = new PhoneNumberWorkerBundle(
                config(2, Duration.ofSeconds(1)),
                catalog,
                runtime,
                propertyIndex,
                (workerId, ignored) -> workerId.endsWith("001")
                        ? first
                        : second
        );

        bundle.start();
        bundle.start();

        ArgumentCaptor<WorkerGroupDescriptor> group =
                ArgumentCaptor.forClass(WorkerGroupDescriptor.class);
        verify(catalog).upsertWorkerGroup(group.capture());
        assertThat(group.getValue().workerGroupId())
                .isEqualTo("scenario-phone-number-workers");
        assertThat(group.getValue().eventCodes())
                .containsExactlyInAnyOrderElementsOf(
                        PhoneNumberCapability.EVENT_CODES
                );
        ArgumentCaptor<WorkerDeclaration> workers =
                ArgumentCaptor.forClass(WorkerDeclaration.class);
        verify(runtime, times(2)).upsertWorker(workers.capture());
        assertThat(workers.getAllValues())
                .extracting(WorkerDeclaration::workerId)
                .containsExactly(
                        "scenario-phone-number-worker-001",
                        "scenario-phone-number-worker-002"
                );
        assertThat(workers.getAllValues())
                .allSatisfy(declaration ->
                        assertThat(declaration.endpointManagerId())
                                .isEqualTo("scenario-websocket")
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
                config(1, Duration.ofSeconds(1)),
                catalog,
                runtime,
                acceptedIndex(),
                factory
        );

        assertThatThrownBy(bundle::start)
                .isInstanceOf(ScenarioWorkerAssemblyException.class)
                .extracting("errorCode")
                .isEqualTo(14002);

        verify(factory, times(0)).create(any(), any());
    }

    @Test
    void rejectedIndexProjectionDoesNotRollBackWorkerStartup() {
        WorkerPropertyIndexRuntime index = mock(
                WorkerPropertyIndexRuntime.class
        );
        when(index.updateIndexedProperties(any(), any(), any()))
                .thenReturn(Map.of(
                        "index.worker.region",
                        new WorkerRuntimeResult(
                                WorkerRuntimeStatus.REJECTED,
                                "projection rejected"
                        )
                ));
        WebSocketWorkerTransport worker = connectedWorker();
        PhoneNumberWorkerBundle bundle = new PhoneNumberWorkerBundle(
                config(1, Duration.ofSeconds(1)),
                acceptedCatalog(),
                acceptedRuntime(),
                index,
                (workerId, ignored) -> worker
        );

        bundle.start();

        verify(worker).start();
        bundle.close();
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
                config(2, Duration.ofSeconds(1)),
                catalog,
                runtime,
                acceptedIndex(),
                (workerId, ignored) -> workerId.endsWith("001")
                        ? first
                        : second
        );

        assertThatThrownBy(bundle::start)
                .isInstanceOf(ScenarioWorkerAssemblyException.class)
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
                config(1, Duration.ofMillis(1)),
                acceptedCatalog(),
                acceptedRuntime(),
                acceptedIndex(),
                (workerId, ignored) -> worker
        );

        assertThatThrownBy(bundle::start)
                .isInstanceOf(ScenarioWorkerAssemblyException.class)
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

    private static WorkerPropertyIndexRuntime acceptedIndex() {
        WorkerPropertyIndexRuntime index = mock(
                WorkerPropertyIndexRuntime.class
        );
        when(index.updateIndexedProperties(any(), any(), any()))
                .thenReturn(Map.of(
                        "index.worker.region",
                        accepted(WorkerRuntimeStatus.OK)
                ));
        return index;
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

    private static ScenarioWorkerBundleConfig config(
            int workerCount,
            Duration connectTimeout
    ) {
        return new ScenarioWorkerBundleConfig(
                "phone-number",
                "scenario-websocket",
                workerUri(),
                "scenario-phone-number-workers",
                "scenario-phone-number-worker-",
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
