package com.xa.mass.command.event;

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
        assertEquals(TargetScope.OPERATOR, descriptor.getTargetScope());
    }
}
