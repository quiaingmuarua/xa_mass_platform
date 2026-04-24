package com.xa.mass.gateway.server;

import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.gateway.dispatcher.DispatcherContext;
import com.xa.mass.gateway.dispatcher.MessageHandlerRegistry;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.queue.GsonMessageCodec;
import com.xa.mass.transport.WorkerEndpointRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MassServerBuilderTest {

    private DispatcherContext dispatcherContext;

    @BeforeEach
    void setUp() {
        dispatcherContext = new DispatcherContext(
                null,
                Mockito.mock(WorkerEndpointRegistry.class),
                new GsonMessageCodec()
        );
        dispatcherContext.setMessageHandlerRegistry(new MessageHandlerRegistry());
    }

    @Test
    void rejectsDirectControlEventTupleRegistration() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> MassServerBuilder.create()
                        .withDispatcherContext(dispatcherContext)
                        .registerHandler(MessageType.CONTROL,
                                WorkerControlEventProtocol.SUB_MSG_TYPE,
                                msg -> Collections.emptyList()));

        assertTrue(error.getMessage().contains("CONTROL/event"));
    }
}
