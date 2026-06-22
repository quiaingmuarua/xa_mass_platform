package com.xa.mass.transport.routing;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoutingEnvelopeTest {

    @Test
    void envelopeShapeStaysMinimal() {
        assertEquals(
                java.util.List.of("envelopeId", "target", "payload", "diagnostics", "createdAtEpochMillis"),
                Arrays.stream(RoutingEnvelope.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList()
        );
        assertEquals(
                java.util.List.of("ownerKind", "ownerRef"),
                Arrays.stream(RoutingTarget.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList()
        );
    }

    @Test
    void targetAllowsOnlyKnownOwnerKinds() {
        RoutingTarget adapter = RoutingTarget.adapter("mailbox-1");
        RoutingTarget engine = RoutingTarget.engine("corr-1");
        RoutingTarget resultIngress = RoutingTarget.resultIngress("corr-2");

        assertEquals(RoutingOwnerKinds.ADAPTER, adapter.ownerKind());
        assertEquals("mailbox-1", adapter.ownerRef());
        assertEquals(RoutingOwnerKinds.ENGINE, engine.ownerKind());
        assertEquals("corr-1", engine.ownerRef());
        assertEquals(RoutingOwnerKinds.RESULT_INGRESS, resultIngress.ownerKind());
        assertEquals("corr-2", resultIngress.ownerRef());
        assertThrows(IllegalArgumentException.class, () -> new RoutingTarget("worker", "worker-1"));
    }

    @Test
    void diagnosticsAreBoundedOutOfRoutingFacts() {
        RoutingEnvelope envelope = new RoutingEnvelope(
                "env-1",
                RoutingTarget.adapter("mailbox-1"),
                "{\"message\":\"hello\"}",
                Map.of("traceId", "trace-1"),
                1L
        );

        assertEquals("env-1", envelope.envelopeId());
        assertEquals("mailbox-1", envelope.target().ownerRef());
        assertEquals("{\"message\":\"hello\"}", envelope.payload());
        assertEquals("trace-1", envelope.diagnostics().get("traceId"));
        assertThrows(UnsupportedOperationException.class, () -> envelope.diagnostics().put("routeKey", "route-1"));
    }
}
