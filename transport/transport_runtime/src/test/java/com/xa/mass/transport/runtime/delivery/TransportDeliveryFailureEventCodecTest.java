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
    void encodesFailureSnapshotWithoutFullCommandOrPacketPayload() {
        DeliveryCommand command = DeliveryCommandFixtures.command("msg-no-owner", "worker-1", null);
        DeliveryObservationGroupContext groupContext =
                new DeliveryObservationGroupContext("websocket", "websocket", null, 42L);
        DeliveryObservationItemSnapshot itemSnapshot =
                DeliveryObservationItemSnapshot.from(command, null);
        DispatchOutcome outcome = DeliveryObservationSupport.outcome(
                groupContext,
                itemSnapshot,
                DispatchOutcomeStatus.NO_ENDPOINT,
                true,
                "transport endpoint is unavailable after assignment"
        );
        TransportDeliveryFailureEventCodec codec = new TransportDeliveryFailureEventCodec();

        String json = codec.encode(new TransportDeliveryFailureEvent(
                groupContext,
                itemSnapshot,
                outcome,
                "transport endpoint is unavailable after assignment"
        ));
        TransportDeliveryFailureEvent decoded = codec.decode(json);

        assertFalse(json.contains("commandBatchJson"), json);
        assertFalse(json.contains("\"payload\""), json);
        assertFalse(json.contains("\"correlation\""), json);
        assertEquals(command.getCommandId(), decoded.itemSnapshot().commandId());
        assertEquals("worker-1", decoded.itemSnapshot().selectedWorkerId());
        assertEquals("msg-no-owner", decoded.itemSnapshot().messageId());
        assertNull(decoded.groupContext().targetTransportNodeId());
        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, decoded.outcome().getStatus());
        assertNull(decoded.outcome().getTransportNodeId());
    }
}
