package com.xa.mass.gateway.server;

import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.gateway.model.enums.MessageType;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MassServerBuilderTest {

    @Test
    void rejectsDirectControlEventTupleRegistration() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> MassServerBuilder.create()
                        .registerHandler("demoApp", MessageType.CONTROL,
                                WorkerControlEventProtocol.SUB_MSG_TYPE,
                                msg -> Collections.emptyList()));

        assertTrue(error.getMessage().contains("CONTROL/event"));
    }
}
