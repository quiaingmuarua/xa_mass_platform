package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.AdapterDispatchRequest;
import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TaskResultReport;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.TransportRuntimeRegistry;
import com.xa.mass.transport.runtime.route.InMemoryTransportRouteOwnerStore;
import com.xa.mass.transport.worker.WorkerAdapter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransportDeliveryCommandListenerTest {

    @Test
    void adapterUnavailableEmitsOneRetryableFailure() {
        InMemoryTransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore(30_000L, "node-1");
        TransportRuntimeRegistry registry = new TransportRuntimeRegistry(
                (TaskResultReport report) -> true,
                routeOwnerStore,
                List.of(TransportBinding.builder(new NoopWorkerAdapter("socket")).build())
        );
        routeOwnerStore.claimRouteOwner("worker-1", "websocket", "route-1", "conn-1", "test");
        RecordingFailureHandler failures = new RecordingFailureHandler();
        TransportDeliveryCommandListener listener = new TransportDeliveryCommandListener(
                registry,
                routeOwnerStore,
                "node-1",
                failures,
                null
        );
        DeliveryCommand command = DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1");

        List<DispatchOutcome> outcomes = listener.onDeliveryCommandBatch(DeliveryCommandFixtures.batch("node-1", command));

        assertEquals(List.of(DispatchOutcomeStatus.UNAVAILABLE), outcomes.stream().map(DispatchOutcome::getStatus).toList());
        assertEquals(1, failures.events.size());
        assertEquals(command.getCommandId(), failures.events.get(0).outcome().getDeliveryId());
        assertEquals(DispatchOutcomeStatus.UNAVAILABLE, failures.events.get(0).outcome().getStatus());
    }

    private static final class RecordingFailureHandler implements TransportDeliveryFailureHandler {
        private final List<TransportDeliveryFailureEvent> events = new ArrayList<>();

        @Override
        public boolean handle(TransportDeliveryFailureEvent event) {
            events.add(event);
            return true;
        }
    }

    private static final class NoopWorkerAdapter implements WorkerAdapter {
        private final String adapterId;

        private NoopWorkerAdapter(String adapterId) {
            this.adapterId = adapterId;
        }

        @Override
        public String protocol() {
            return adapterId;
        }

        @Override
        public List<DispatchOutcome> dispatch(List<AdapterDispatchRequest> requests) {
            return List.of();
        }
    }
}
