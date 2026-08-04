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

import com.xa.mass.kernel.worker.WorkerPropertyIndexRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDeclaration;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeStatus;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.transport.websocket.WebSocketWorkerTransport;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class ScenarioWorkerGroupTest {

    @Test
    void registersGroupThenStartsWorkersWithSharedDefinitions() {
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        WorkerRuntime runtime = mock(WorkerRuntime.class);
        WorkerPropertyIndexRuntime propertyIndex = acceptedIndex();
        when(catalog.upsertWorkerGroup(any())).thenReturn(
                accepted(WorkerRuntimeStatus.OK)
        );
        when(runtime.registerWorker(any())).thenReturn(
                accepted(WorkerRuntimeStatus.NOOP)
        );
        when(runtime.updateWorkerProperties(any(), any(), any())).thenReturn(
                accepted(WorkerRuntimeStatus.OK)
        );
        WebSocketWorkerTransport first = connectedWorker();
        WebSocketWorkerTransport second = connectedWorker();
        List<WorkerEventDefinition<?>> definitions =
                PhoneNumberWorkerEvents.definitions();
        List<List<WorkerEventDefinition<?>>> receivedDefinitions =
                new ArrayList<>();
        ScenarioWorkerGroup group = new ScenarioWorkerGroup(
                config(2, Duration.ofSeconds(1)),
                definitions,
                catalog,
                runtime,
                propertyIndex,
                (workerId, ignored, workerDefinitions) -> {
                    receivedDefinitions.add(workerDefinitions);
                    return workerId.endsWith("001") ? first : second;
                }
        );

        group.start();
        group.start();

        ArgumentCaptor<WorkerGroupDescriptor> groupDescriptor =
                ArgumentCaptor.forClass(WorkerGroupDescriptor.class);
        verify(catalog).upsertWorkerGroup(groupDescriptor.capture());
        assertThat(groupDescriptor.getValue().workerGroupId())
                .isEqualTo("scenario-phone-number-workers");
        assertThat(groupDescriptor.getValue().attributes())
                .containsEntry("capability", "libphonenumber");
        assertThat(groupDescriptor.getValue().eventCodes())
                .containsExactlyInAnyOrderElementsOf(eventCodes());
        ArgumentCaptor<WorkerDeclaration> workers =
                ArgumentCaptor.forClass(WorkerDeclaration.class);
        verify(runtime, times(2)).registerWorker(workers.capture());
        verify(runtime, times(2)).updateWorkerProperties(
                any(), any(), any()
        );
        assertThat(workers.getAllValues())
                .extracting(WorkerDeclaration::workerId)
                .containsExactly(
                        "scenario-phone-number-worker-001",
                        "scenario-phone-number-worker-002"
                );
        assertThat(receivedDefinitions).hasSize(2);
        assertThat(receivedDefinitions.get(0))
                .isSameAs(receivedDefinitions.get(1));
        InOrder startup = inOrder(
                catalog,
                runtime,
                propertyIndex,
                first,
                second
        );
        startup.verify(catalog).upsertWorkerGroup(any());
        startup.verify(runtime).registerWorker(any());
        startup.verify(runtime).updateWorkerProperties(any(), any(), any());
        startup.verify(propertyIndex).updateIndexedProperties(
                any(), any(), any()
        );
        startup.verify(first).start();
        startup.verify(runtime).registerWorker(any());
        startup.verify(runtime).updateWorkerProperties(any(), any(), any());
        startup.verify(propertyIndex).updateIndexedProperties(
                any(), any(), any()
        );
        startup.verify(second).start();

        group.close();
        group.close();

        InOrder closeOrder = inOrder(first, second);
        closeOrder.verify(second).close();
        closeOrder.verify(first).close();
    }

    @Test
    void rejectedGroupStopsBeforeWorkerCreation() {
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        when(catalog.upsertWorkerGroup(any())).thenReturn(
                new WorkerRuntimeResult(
                        WorkerRuntimeStatus.INVALID,
                        "invalid group"
                )
        );
        ScenarioWorkerGroup.WorkerFactory factory = mock(
                ScenarioWorkerGroup.WorkerFactory.class
        );
        ScenarioWorkerGroup group = new ScenarioWorkerGroup(
                config(1, Duration.ofSeconds(1)),
                PhoneNumberWorkerEvents.definitions(),
                catalog,
                mock(WorkerRuntime.class),
                acceptedIndex(),
                factory
        );

        assertThatThrownBy(group::start)
                .isInstanceOf(ScenarioWorkerAssemblyException.class)
                .extracting("errorCode")
                .isEqualTo(14002);

        verify(factory, times(0)).create(any(), any(), any());
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
        ScenarioWorkerGroup group = new ScenarioWorkerGroup(
                config(1, Duration.ofSeconds(1)),
                PhoneNumberWorkerEvents.definitions(),
                acceptedCatalog(),
                acceptedRuntime(),
                index,
                (workerId, ignored, definitions) -> worker
        );

        group.start();

        verify(worker).start();
        group.close();
    }

    @Test
    void partialTransportFailureClosesAllCreatedWorkers() {
        WebSocketWorkerTransport first = connectedWorker();
        WebSocketWorkerTransport second = connectedWorker();
        doThrow(new IllegalStateException("start failed"))
                .when(second)
                .start();
        ScenarioWorkerGroup group = new ScenarioWorkerGroup(
                config(2, Duration.ofSeconds(1)),
                PhoneNumberWorkerEvents.definitions(),
                acceptedCatalog(),
                acceptedRuntime(),
                acceptedIndex(),
                (workerId, ignored, definitions) ->
                        workerId.endsWith("001") ? first : second
        );

        assertThatThrownBy(group::start)
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
        ScenarioWorkerGroup group = new ScenarioWorkerGroup(
                config(1, Duration.ofMillis(1)),
                PhoneNumberWorkerEvents.definitions(),
                acceptedCatalog(),
                acceptedRuntime(),
                acceptedIndex(),
                (workerId, ignored, definitions) -> worker
        );

        assertThatThrownBy(group::start)
                .isInstanceOf(ScenarioWorkerAssemblyException.class)
                .extracting("errorCode")
                .isEqualTo(14005);

        verify(worker).close();
    }

    private static WorkerResourceCatalog acceptedCatalog() {
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        when(catalog.upsertWorkerGroup(any())).thenReturn(
                accepted(WorkerRuntimeStatus.OK)
        );
        return catalog;
    }

    private static WorkerRuntime acceptedRuntime() {
        WorkerRuntime runtime = mock(WorkerRuntime.class);
        when(runtime.registerWorker(any())).thenReturn(
                accepted(WorkerRuntimeStatus.OK)
        );
        when(runtime.updateWorkerProperties(any(), any(), any())).thenReturn(
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

    private static ScenarioWorkerGroupConfig config(
            int workerCount,
            Duration connectTimeout
    ) {
        return new ScenarioWorkerGroupConfig(
                "scenario-phone-number-workers",
                Map.of("capability", "libphonenumber"),
                eventCodes(),
                "scenario-websocket",
                workerUri(),
                workers(workerCount),
                Duration.ofSeconds(10),
                Duration.ofMillis(250),
                connectTimeout
        );
    }

    private static List<String> eventCodes() {
        return PhoneNumberWorkerEvents.definitions().stream()
                .map(WorkerEventDefinition::eventCode)
                .toList();
    }

    private static List<ScenarioWorkerConfig> workers(int count) {
        List<ScenarioWorkerConfig> workers = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            workers.add(new ScenarioWorkerConfig(
                    "scenario-phone-number-worker-"
                            + String.format("%03d", index),
                    Map.of("region", "configured"),
                    Map.of("index.worker.region", "configured")
            ));
        }
        return workers;
    }

    private static URI workerUri() {
        return URI.create(
                "ws://127.0.0.1:18083"
                        + "/api/v1/worker-delivery/websocket"
        );
    }
}
