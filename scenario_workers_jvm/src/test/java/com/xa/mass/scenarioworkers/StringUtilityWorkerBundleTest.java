package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

class StringUtilityWorkerBundleTest {

    @Test
    void upsertsCompleteCapabilityGroupAndDeterministicWorkers() {
        WorkerResourceCatalog catalog = acceptedCatalog();
        WorkerRuntime runtime = acceptedRuntime();
        WebSocketWorkerTransport first = connectedWorker();
        WebSocketWorkerTransport second = connectedWorker();
        StringUtilityWorkerBundle bundle =
                new StringUtilityWorkerBundle(
                        config(2, Duration.ofSeconds(1)),
                        catalog,
                        runtime,
                        acceptedIndex(),
                        (workerId, ignored) ->
                                workerId.endsWith("001")
                                        ? first
                                        : second
                );

        bundle.start();
        bundle.start();

        ArgumentCaptor<WorkerGroupDescriptor> group =
                ArgumentCaptor.forClass(WorkerGroupDescriptor.class);
        verify(catalog).upsertWorkerGroup(group.capture());
        assertThat(group.getValue().workerGroupId())
                .isEqualTo("scenario-string-utils-workers");
        assertThat(group.getValue().eventCodes())
                .containsExactlyInAnyOrderElementsOf(
                        StringUtilityCapability.EVENT_CODES
                );
        ArgumentCaptor<WorkerDeclaration> workers =
                ArgumentCaptor.forClass(WorkerDeclaration.class);
        verify(runtime, times(2)).upsertWorker(workers.capture());
        assertThat(workers.getAllValues())
                .extracting(WorkerDeclaration::workerId)
                .containsExactly(
                        "scenario-string-utils-worker-001",
                        "scenario-string-utils-worker-002"
                );
        assertThat(workers.getAllValues())
                .allSatisfy(declaration ->
                        assertThat(declaration.endpointManagerId())
                                .isEqualTo("scenario-websocket")
                );

        bundle.close();
        bundle.close();

        InOrder closeOrder = inOrder(first, second);
        closeOrder.verify(second).close();
        closeOrder.verify(first).close();
    }

    @Test
    void rejectedWorkerUpsertStopsBeforeTransportCreation() {
        WorkerResourceCatalog catalog = acceptedCatalog();
        WorkerRuntime runtime = mock(WorkerRuntime.class);
        when(runtime.upsertWorker(any())).thenReturn(
                new WorkerRuntimeResult(
                        WorkerRuntimeStatus.CONFLICT,
                        "conflict"
                )
        );
        StringUtilityWorkerBundle.WorkerFactory factory = mock(
                StringUtilityWorkerBundle.WorkerFactory.class
        );
        StringUtilityWorkerBundle bundle =
                new StringUtilityWorkerBundle(
                        config(1, Duration.ofSeconds(1)),
                        catalog,
                        runtime,
                        acceptedIndex(),
                        factory
                );

        assertThatThrownBy(bundle::start)
                .isInstanceOf(ScenarioWorkerAssemblyException.class)
                .extracting("errorCode")
                .isEqualTo(14007);

        verify(factory, times(0)).create(any(), any());
    }

    private static WorkerResourceCatalog acceptedCatalog() {
        WorkerResourceCatalog catalog = mock(
                WorkerResourceCatalog.class
        );
        when(catalog.upsertWorkerGroup(any())).thenReturn(
                new WorkerRuntimeResult(WorkerRuntimeStatus.OK)
        );
        return catalog;
    }

    private static WorkerRuntime acceptedRuntime() {
        WorkerRuntime runtime = mock(WorkerRuntime.class);
        when(runtime.upsertWorker(any())).thenReturn(
                new WorkerRuntimeResult(WorkerRuntimeStatus.NOOP)
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
                        new WorkerRuntimeResult(WorkerRuntimeStatus.OK)
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

    private static ScenarioWorkerBundleConfig config(
            int workerCount,
            Duration connectTimeout
    ) {
        return new ScenarioWorkerBundleConfig(
                "string-utils",
                "scenario-websocket",
                workerUri(),
                "scenario-string-utils-workers",
                "scenario-string-utils-worker-",
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
