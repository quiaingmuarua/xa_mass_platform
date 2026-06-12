package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TaskResultReport;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
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
        TransportRuntimeRegistry registry = new TransportRuntimeRegistry(
                (TaskResultReport report) -> true,
                new InMemoryTransportRouteOwnerStore(),
                List.of(TransportBinding.builder(new NoopWorkerAdapter("socket")).build())
        );
        RecordingFailureHandler failures = new RecordingFailureHandler();
        TransportDeliveryCommandListener listener = new TransportDeliveryCommandListener(registry, failures, null);
        DeliveryCommand command = DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1");

        List<DispatchOutcome> outcomes = listener.onDeliveryCommandBatch(DeliveryCommandFixtures.batch("node-1", command));

        assertEquals(List.of(DispatchOutcomeStatus.UNAVAILABLE), outcomes.stream().map(DispatchOutcome::getStatus).toList());
        assertEquals(1, failures.events.size());
        assertEquals(command.getCommandId(), failures.events.get(0).itemSnapshot().commandId());
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
        public List<DispatchOutcome> dispatchEnvelopes(List<TransportDispatchEnvelope> envelopes) {
            return List.of();
        }
    }
}
