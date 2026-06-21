package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.channel.TransportResultIngressChannel;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.TransportRuntimeRegistry;
import com.xa.mass.transport.runtime.embedded.TransportDeliveryCommandListener;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransportDeliveryCommandListenerTest {

    @Test
    void adapterUnavailableEmitsOneRetryableFailure() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore();
        TransportRuntimeRegistry registry = new TransportRuntimeRegistry(
                (TransportResultIngressChannel) envelope -> true,
                endpointLeaseStore,
                List.of(TransportBinding.builder("socket", "realtime", new NoopAdapterCommandExecutor())
                        .adapterMailboxKey("socket")
                        .build())
        );
        RecordingFailureHandler failures = new RecordingFailureHandler();
        TransportDeliveryCommandListener listener = new TransportDeliveryCommandListener(
                registry,
                failures,
                null
        );
        DeliveryCommand command = DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1");

        List<DispatchOutcome> outcomes = listener.onDeliveryCommandBatch(batch(command));

        assertEquals(List.of(DispatchOutcomeStatus.UNAVAILABLE), outcomes.stream().map(DispatchOutcome::getStatus).toList());
        assertEquals(1, failures.events.size());
        assertEquals(command.getCommandId(), failures.events.get(0).outcome().getDeliveryId());
        assertEquals(DispatchOutcomeStatus.UNAVAILABLE, failures.events.get(0).outcome().getStatus());
    }

    private static DeliveryCommandBatch batch(DeliveryCommand command) {
        return new DeliveryCommandBatch(
                DeliveryCommandFixtures.mailboxKey(),
                List.of(new DeliveryCommandReference(
                        DeliveryCommandFixtures.mailboxKey(),
                        command.getCommandId()
                )),
                List.of(command)
        );
    }

    private static final class RecordingFailureHandler implements TransportDeliveryFailureHandler {
        private final List<TransportDeliveryFailureEvent> events = new ArrayList<>();

        @Override
        public boolean handle(TransportDeliveryFailureEvent event) {
            events.add(event);
            return true;
        }
    }

    private static final class NoopAdapterCommandExecutor implements AdapterCommandExecutor {
        @Override
        public List<DispatchOutcome> dispatch(List<DeliveryCommand> commands) {
            return List.of();
        }
    }
}
