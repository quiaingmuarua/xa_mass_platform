package com.xa.mass.sdk.event;

import com.xa.mass.base.event.DeliveryAcknowledgementMode;
import com.xa.mass.base.event.EventConvergenceMode;
import com.xa.mass.base.event.PriorityClass;
import com.xa.mass.base.event.ResponseMode;
import com.xa.mass.base.event.TargetScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventDefinitionTest {

    @Test
    void defaultsEventMetadataWithoutChangingRegistrationRequirements() {
        EventDefinition definition = EventDefinition.builder()
                .code("demo.work")
                .name("Demo Work")
                .build();

        assertEquals(PriorityClass.STANDARD, definition.getPriorityClass());
        assertEquals(ResponseMode.FINAL_RESULT, definition.getResponseMode());
        assertEquals(DeliveryAcknowledgementMode.NONE, definition.getDeliveryAcknowledgementMode());
        assertEquals(EventConvergenceMode.FINAL_RESULT, definition.getConvergenceMode());
        assertEquals(TargetScope.WORKER, definition.getTargetScope());
    }

    @Test
    void preservesExplicitEventMetadata() {
        EventDefinition definition = EventDefinition.builder()
                .code("operator.control")
                .name("Operator Control")
                .priorityClass(PriorityClass.CONTROL)
                .responseMode(ResponseMode.ACK)
                .targetScope(TargetScope.OPERATOR)
                .build();

        assertEquals(PriorityClass.CONTROL, definition.getPriorityClass());
        assertEquals(ResponseMode.ACK, definition.getResponseMode());
        assertEquals(DeliveryAcknowledgementMode.HANDLER_ACCEPTED, definition.getDeliveryAcknowledgementMode());
        assertEquals(EventConvergenceMode.NONE, definition.getConvergenceMode());
        assertEquals(TargetScope.OPERATOR, definition.getTargetScope());
    }

    @Test
    void nullMetadataFallsBackToDefaults() {
        EventDefinition definition = EventDefinition.builder()
                .code("null.metadata")
                .name("Null Metadata")
                .priorityClass(null)
                .responseMode(null)
                .targetScope(null)
                .build();

        assertEquals(PriorityClass.STANDARD, definition.getPriorityClass());
        assertEquals(ResponseMode.FINAL_RESULT, definition.getResponseMode());
        assertEquals(DeliveryAcknowledgementMode.NONE, definition.getDeliveryAcknowledgementMode());
        assertEquals(EventConvergenceMode.FINAL_RESULT, definition.getConvergenceMode());
        assertEquals(TargetScope.WORKER, definition.getTargetScope());
    }

    @Test
    void splitResponseSemanticsCanBeSetWithoutChangingCompatibilitySummary() {
        EventDefinition definition = EventDefinition.builder()
                .code("stage.progress")
                .name("Stage Progress")
                .responseMode(ResponseMode.ACK)
                .deliveryAcknowledgementMode(DeliveryAcknowledgementMode.DELIVERY_ACCEPTED)
                .convergenceMode(EventConvergenceMode.STREAM)
                .build();

        assertEquals(ResponseMode.ACK, definition.getResponseMode());
        assertEquals(DeliveryAcknowledgementMode.DELIVERY_ACCEPTED, definition.getDeliveryAcknowledgementMode());
        assertEquals(EventConvergenceMode.STREAM, definition.getConvergenceMode());
    }
}
