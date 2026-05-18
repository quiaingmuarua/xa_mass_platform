package com.xa.mass.sdk.event;

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
        assertEquals(TargetScope.WORKER, definition.getTargetScope());
    }
}
