package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TransportDeliveryFailureEventCodecTest {

    @Test
    void encodesFailureSnapshotWithoutFullCommandOrPacketPayload() {
        DeliveryCommand command = DeliveryCommandFixtures.command("msg-no-owner", "worker-1", null);
        DispatchOutcome outcome = DispatchOutcome.fromCommand(
                command,
                DispatchOutcomeStatus.NO_ENDPOINT,
                true,
                "transport endpoint is unavailable after assignment"
        );
        TransportDeliveryFailureEventCodec codec = new TransportDeliveryFailureEventCodec();

        String json = codec.encode(new TransportDeliveryFailureEvent(
                outcome,
                "transport endpoint is unavailable after assignment"
        ));
        TransportDeliveryFailureEvent decoded = codec.decode(json);

        assertFalse(json.contains("commandBatchJson"), json);
        assertFalse(json.contains("\"payload\""), json);
        assertFalse(json.contains("\"correlation\""), json);
        assertFalse(json.contains("groupContext"), json);
        assertFalse(json.contains("itemSnapshot"), json);
        assertEquals(command.getCommandId(), decoded.outcome().getDeliveryId());
        assertEquals("worker-1", decoded.outcome().getSelectedWorkerId());
        assertEquals("msg-no-owner", decoded.outcome().getMessageId());
        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, decoded.outcome().getStatus());
        assertFalse(json.contains("transportNodeId"), json);
        assertFalse(json.contains("connectionId"), json);
        assertFalse(json.contains("routeKey"), json);
        assertFalse(json.contains("adapterId"), json);
        assertFalse(json.contains("deliveryQueueKey"), json);
    }
}
