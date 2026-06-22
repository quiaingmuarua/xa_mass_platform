package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchOutcomeFactoryTest {

    @Test
    void buildsOutcomeFromFlatItemFactsWithoutPayloadParsing() {
        DispatchRoutingItem item = new DispatchRoutingItem(
                "delivery-1",
                "worker-1",
                "{\"selectedWorkerId\":\"wrong-worker\",\"deliveryId\":\"wrong-delivery\"}",
                "corr-1",
                0L,
                1L
        );

        DispatchOutcome outcome = DispatchOutcomeFactory.noEndpoint(item, "missing selected worker endpoint");

        assertEquals("delivery-1", outcome.getDeliveryId());
        assertEquals("worker-1", outcome.getSelectedWorkerId());
        assertEquals("corr-1", outcome.getCorrelationRef());
        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, outcome.getStatus());
        assertTrue(outcome.isRetryable());
        assertEquals("missing selected worker endpoint", outcome.getReason());
    }
}
