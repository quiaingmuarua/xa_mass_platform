package com.xa.mass.transport.runtime.embedded;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdapterCommandExecutorsTest {

    @Test
    void mapsSuccessfulFinalHopAttemptToDeliveredOutcome() {
        AdapterCommandExecutor executor = AdapterCommandExecutors.perMessage("test", item -> true);

        List<DispatchOutcome> outcomes = executor.dispatch(List.of(item("msg-1")));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.DELIVERED, outcomes.get(0).getStatus());
        assertEquals("worker-1", outcomes.get(0).getSelectedWorkerId());
        assertEquals("corr-msg-1", outcomes.get(0).getCorrelationRef());
    }

    @Test
    void mapsFalseFinalHopAttemptToRetryableNoEndpoint() {
        AdapterCommandExecutor executor = AdapterCommandExecutors.perMessage("test", item -> false);

        DispatchOutcome outcome = executor.dispatch(List.of(item("msg-1"))).get(0);

        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, outcome.getStatus());
        assertTrue(outcome.isRetryable());
    }

    @Test
    void mapsRuntimeExceptionToRetryableFailedOutcome() {
        AdapterCommandExecutor executor = AdapterCommandExecutors.perMessage("test", item -> {
            throw new IllegalStateException("send failed");
        });

        DispatchOutcome outcome = executor.dispatch(List.of(item("msg-1"))).get(0);

        assertEquals(DispatchOutcomeStatus.FAILED, outcome.getStatus());
        assertTrue(outcome.isRetryable());
        assertEquals("send failed", outcome.getReason());
    }

    @Test
    void mapsNullItemToInvalidOutcome() {
        AdapterCommandExecutor executor = AdapterCommandExecutors.perMessage("test", item -> true);

        DispatchOutcome outcome = executor.dispatch(java.util.Collections.singletonList(null)).get(0);

        assertEquals(DispatchOutcomeStatus.INVALID, outcome.getStatus());
        assertEquals("request must not be null", outcome.getReason());
    }

    @Test
    void rejectsMissingTemplateInputs() {
        assertThrows(IllegalArgumentException.class, () -> AdapterCommandExecutors.perMessage(" ", item -> true));
        assertThrows(NullPointerException.class, () -> AdapterCommandExecutors.perMessage("test", null));
    }

    private DispatchMessage item(String messageId) {
        return new DispatchMessage(
                "delivery-" + messageId,
                "worker-1",
                "{\"messageId\":\"" + messageId + "\"}",
                "corr-" + messageId,
                0L,
                1L
        );
    }
}
