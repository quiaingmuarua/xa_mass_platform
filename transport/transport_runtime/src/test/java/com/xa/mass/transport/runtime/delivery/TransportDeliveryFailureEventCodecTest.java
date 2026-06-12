package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class TransportDeliveryFailureEventCodecTest {

    @Test
    void encodesSingleFailureCommandWithoutRequiringTargetTransportNode() {
        DeliveryCommand command = DeliveryCommandFixtures.command("msg-no-owner", "worker-1", null);
        DispatchOutcome outcome = DispatchOutcome.noEndpoint(command, "transport endpoint is unavailable after assignment");
        TransportDeliveryFailureEventCodec codec = new TransportDeliveryFailureEventCodec();

        String json = codec.encode(new TransportDeliveryFailureEvent(
                command,
                outcome,
                "transport endpoint is unavailable after assignment"
        ));
        TransportDeliveryFailureEvent decoded = codec.decode(json);

        assertFalse(json.contains("commandBatchJson"), json);
        assertEquals(command.getCommandId(), decoded.command().getCommandId());
        assertEquals("worker-1", decoded.command().getSelectedWorkerId());
        assertNull(decoded.command().getTargetTransportNodeId());
        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, decoded.outcome().getStatus());
        assertNull(decoded.outcome().getTransportNodeId());
    }
}
