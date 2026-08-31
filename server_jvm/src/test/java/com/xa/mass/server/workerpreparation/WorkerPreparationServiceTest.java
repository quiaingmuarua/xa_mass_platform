package com.xa.mass.server.workerpreparation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.server.workerbinding.WorkerBindingService;
import com.xa.mass.server.workerbinding.WorkerEndpointBinding;
import com.xa.mass.server.workerbinding.WorkerTransportType;
import com.xa.mass.server.workeridentity.WorkerIdentityService;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class WorkerPreparationServiceTest {

    @Test
    void resolvesIdentityBeforeBindingAndReturnsOnePreparedCoordinate() {
        WorkerIdentityService identities = mock(WorkerIdentityService.class);
        WorkerBindingService bindings = mock(WorkerBindingService.class);
        Map<String, Object> properties = Map.of(
                "clientWorkerKey",
                "installation-1"
        );
        when(identities.register(
                "group-1",
                WorkerRegistrationKind.CLIENT_KEY,
                properties
        ))
                .thenReturn("worker-1");
        when(bindings.bind(
                "group-1",
                "worker-1",
                WorkerRegistrationKind.CLIENT_KEY,
                WorkerTransportType.WEBSOCKET,
                properties
        )).thenReturn(new WorkerEndpointBinding(
                "adapter-1",
                WorkerTransportType.WEBSOCKET,
                URI.create("ws://127.0.0.1:18083/connect")
        ));

        WorkerPreparationService.PreparedWorker prepared =
                new WorkerPreparationService(
                        identities,
                        bindings
                ).prepareAll(
                        "group-1",
                        WorkerRegistrationKind.CLIENT_KEY,
                        WorkerTransportType.WEBSOCKET,
                        List.of(properties)
                ).get(0);

        assertThat(prepared.workerId()).isEqualTo("worker-1");
        assertThat(prepared.endpointUri()).isEqualTo(
                URI.create("ws://127.0.0.1:18083/connect")
        );
        InOrder order = inOrder(identities, bindings);
        order.verify(identities).register(
                "group-1",
                WorkerRegistrationKind.CLIENT_KEY,
                properties
        );
        order.verify(bindings).bind(
                "group-1",
                "worker-1",
                WorkerRegistrationKind.CLIENT_KEY,
                WorkerTransportType.WEBSOCKET,
                properties
        );
    }

    @Test
    void repeatedPrepareConvergesAfterACompletedIdentityStage() {
        WorkerIdentityService identities = mock(WorkerIdentityService.class);
        WorkerBindingService bindings = mock(WorkerBindingService.class);
        Map<String, Object> properties = Map.of(
                "clientWorkerKey",
                "installation-1"
        );
        when(identities.register(
                "group-1",
                WorkerRegistrationKind.CLIENT_KEY,
                properties
        ))
                .thenReturn("worker-1");
        when(bindings.bind(
                "group-1",
                "worker-1",
                WorkerRegistrationKind.CLIENT_KEY,
                WorkerTransportType.WEBSOCKET,
                properties
        )).thenThrow(new IllegalStateException("endpoint unavailable"))
                .thenReturn(new WorkerEndpointBinding(
                        "adapter-1",
                        WorkerTransportType.WEBSOCKET,
                        URI.create("ws://127.0.0.1:18083/connect")
                ));
        WorkerPreparationService service = new WorkerPreparationService(
                identities,
                bindings
        );

        assertThatThrownBy(() -> service.prepareAll(
                "group-1",
                WorkerRegistrationKind.CLIENT_KEY,
                WorkerTransportType.WEBSOCKET,
                List.of(properties)
        )).isInstanceOf(IllegalStateException.class);
        assertThat(service.prepareAll(
                "group-1",
                WorkerRegistrationKind.CLIENT_KEY,
                WorkerTransportType.WEBSOCKET,
                List.of(properties)
        ).get(0).workerId()).isEqualTo("worker-1");

        verify(identities, times(2)).register(
                "group-1",
                WorkerRegistrationKind.CLIENT_KEY,
                properties
        );
        verify(bindings, times(2)).bind(
                "group-1",
                "worker-1",
                WorkerRegistrationKind.CLIENT_KEY,
                WorkerTransportType.WEBSOCKET,
                properties
        );
    }

    @Test
    void validatesWholeBatchThenPreparesInRequestOrder() {
        WorkerIdentityService identities = mock(WorkerIdentityService.class);
        WorkerBindingService bindings = mock(WorkerBindingService.class);
        Map<String, Object> first = Map.of(
                "labInventoryKey", "workers.jsonl",
                "labInventoryLine", 1L
        );
        Map<String, Object> second = Map.of(
                "labInventoryKey", "workers.jsonl",
                "labInventoryLine", 2L
        );
        when(identities.registrationKey(
                WorkerRegistrationKind.SCENARIO_LAB,
                first
        )).thenReturn("lab:workers.jsonl:1");
        when(identities.registrationKey(
                WorkerRegistrationKind.SCENARIO_LAB,
                second
        )).thenReturn("lab:workers.jsonl:2");
        when(identities.register(
                "group-1",
                WorkerRegistrationKind.SCENARIO_LAB,
                first
        ))
                .thenReturn("worker-1");
        when(identities.register(
                "group-1",
                WorkerRegistrationKind.SCENARIO_LAB,
                second
        ))
                .thenReturn("worker-2");
        when(bindings.bind(
                "group-1",
                "worker-1",
                WorkerRegistrationKind.SCENARIO_LAB,
                WorkerTransportType.WEBSOCKET,
                first
        )).thenReturn(new WorkerEndpointBinding(
                "adapter-1",
                WorkerTransportType.WEBSOCKET,
                URI.create("ws://127.0.0.1:18083/one")
        ));
        when(bindings.bind(
                "group-1",
                "worker-2",
                WorkerRegistrationKind.SCENARIO_LAB,
                WorkerTransportType.WEBSOCKET,
                second
        )).thenReturn(new WorkerEndpointBinding(
                "adapter-1",
                WorkerTransportType.WEBSOCKET,
                URI.create("ws://127.0.0.1:18083/two")
        ));

        List<WorkerPreparationService.PreparedWorker> prepared =
                new WorkerPreparationService(identities, bindings)
                        .prepareAll(
                                "group-1",
                                WorkerRegistrationKind.SCENARIO_LAB,
                                WorkerTransportType.WEBSOCKET,
                                List.of(first, second)
                        );

        assertThat(prepared)
                .extracting(
                        WorkerPreparationService.PreparedWorker::workerId
                )
                .containsExactly("worker-1", "worker-2");
        InOrder order = inOrder(identities, bindings);
        order.verify(identities).register(
                "group-1",
                WorkerRegistrationKind.SCENARIO_LAB,
                first
        );
        order.verify(bindings).bind(
                "group-1",
                "worker-1",
                WorkerRegistrationKind.SCENARIO_LAB,
                WorkerTransportType.WEBSOCKET,
                first
        );
        order.verify(identities).register(
                "group-1",
                WorkerRegistrationKind.SCENARIO_LAB,
                second
        );
        order.verify(bindings).bind(
                "group-1",
                "worker-2",
                WorkerRegistrationKind.SCENARIO_LAB,
                WorkerTransportType.WEBSOCKET,
                second
        );
    }

    @Test
    void invalidBatchCreatesNoIdentityOrBindingSideEffects() {
        WorkerIdentityService identities = mock(WorkerIdentityService.class);
        WorkerBindingService bindings = mock(WorkerBindingService.class);
        WorkerPreparationService service = new WorkerPreparationService(
                identities,
                bindings
        );
        Map<String, Object> duplicate = Map.of(
                "labInventoryKey", "workers.jsonl",
                "labInventoryLine", 1L
        );
        when(identities.registrationKey(
                WorkerRegistrationKind.SCENARIO_LAB,
                duplicate
        )).thenReturn("lab:workers.jsonl:1");

        assertThatThrownBy(() -> service.prepareAll(
                "group-1",
                WorkerRegistrationKind.SCENARIO_LAB,
                WorkerTransportType.WEBSOCKET,
                List.of(duplicate, duplicate)
        )).isInstanceOf(com.xa.mass.server.error.ServerException.class);

        verify(identities, times(0)).register(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(bindings, times(0)).bind(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }
}
