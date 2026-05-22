package com.xa.mass.command.event;

import com.xa.mass.base.event.DeliveryAcknowledgementMode;
import com.xa.mass.base.event.EventConvergenceMode;
import com.xa.mass.base.event.PriorityClass;
import com.xa.mass.base.event.ResponseMode;
import com.xa.mass.base.event.TargetScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoreEventDescriptorTest {

    @Test
    void defaultsEventMetadata() {
        CoreEventDescriptor descriptor = CoreEventDescriptor.builder()
                .event("demo.work")
                .build();

        assertEquals(PriorityClass.STANDARD, descriptor.getPriorityClass());
        assertEquals(ResponseMode.FINAL_RESULT, descriptor.getResponseMode());
        assertEquals(DeliveryAcknowledgementMode.NONE, descriptor.getDeliveryAcknowledgementMode());
        assertEquals(EventConvergenceMode.FINAL_RESULT, descriptor.getConvergenceMode());
        assertEquals(TargetScope.WORKER, descriptor.getTargetScope());
    }

    @Test
    void preservesExplicitEventMetadata() {
        CoreEventDescriptor descriptor = CoreEventDescriptor.builder()
                .event("operator.control")
                .priorityClass(PriorityClass.CONTROL)
                .responseMode(ResponseMode.ACK)
                .targetScope(TargetScope.OPERATOR)
                .build();

        assertEquals(PriorityClass.CONTROL, descriptor.getPriorityClass());
        assertEquals(ResponseMode.ACK, descriptor.getResponseMode());
        assertEquals(DeliveryAcknowledgementMode.HANDLER_ACCEPTED, descriptor.getDeliveryAcknowledgementMode());
        assertEquals(EventConvergenceMode.NONE, descriptor.getConvergenceMode());
        assertEquals(TargetScope.OPERATOR, descriptor.getTargetScope());
    }

    @Test
    void explicitSplitResponseSemanticsOverrideCompatibilitySummary() {
        CoreEventDescriptor descriptor = CoreEventDescriptor.builder()
                .event("stage.progress")
                .responseMode(ResponseMode.ACK)
                .deliveryAcknowledgementMode(DeliveryAcknowledgementMode.DELIVERY_ACCEPTED)
                .convergenceMode(EventConvergenceMode.STREAM)
                .build();

        assertEquals(ResponseMode.ACK, descriptor.getResponseMode());
        assertEquals(DeliveryAcknowledgementMode.DELIVERY_ACCEPTED, descriptor.getDeliveryAcknowledgementMode());
        assertEquals(EventConvergenceMode.STREAM, descriptor.getConvergenceMode());
    }
}
