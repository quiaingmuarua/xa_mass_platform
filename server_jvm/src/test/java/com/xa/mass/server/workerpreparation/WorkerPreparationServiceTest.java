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
        when(identities.register("group-1", properties))
                .thenReturn("worker-1");
        when(bindings.bind(
                "group-1",
                "worker-1",
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
                ).prepare(
                        "group-1",
                        WorkerTransportType.WEBSOCKET,
                        properties
                );

        assertThat(prepared.workerId()).isEqualTo("worker-1");
        assertThat(prepared.endpointUri()).isEqualTo(
                URI.create("ws://127.0.0.1:18083/connect")
        );
        InOrder order = inOrder(identities, bindings);
        order.verify(identities).register("group-1", properties);
        order.verify(bindings).bind(
                "group-1",
                "worker-1",
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
        when(identities.register("group-1", properties))
                .thenReturn("worker-1");
        when(bindings.bind(
                "group-1",
                "worker-1",
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

        assertThatThrownBy(() -> service.prepare(
                "group-1",
                WorkerTransportType.WEBSOCKET,
                properties
        )).isInstanceOf(IllegalStateException.class);
        assertThat(service.prepare(
                "group-1",
                WorkerTransportType.WEBSOCKET,
                properties
        ).workerId()).isEqualTo("worker-1");

        verify(identities, times(2)).register("group-1", properties);
        verify(bindings, times(2)).bind(
                "group-1",
                "worker-1",
                WorkerTransportType.WEBSOCKET,
                properties
        );
    }
}
