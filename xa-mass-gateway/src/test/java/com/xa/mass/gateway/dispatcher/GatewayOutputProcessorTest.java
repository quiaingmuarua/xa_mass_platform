package com.xa.mass.gateway.dispatcher;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.gateway.queue.WebSocketTransportFrameCodec;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.NoopWorkerSystemEventChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayOutputProcessorTest {

    private WorkerEndpointRegistry endpointRegistry;
    private GatewayOutputProcessor outputProcessor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MessageTransporter<String, OutboundDelivery> transporter = mock(MessageTransporter.class);
        endpointRegistry = mock(WorkerEndpointRegistry.class);
        DispatcherContext context = new DispatcherContext(
                transporter,
                endpointRegistry,
                new WebSocketTransportFrameCodec(),
                null,
                NoopWorkerSystemEventChannel.INSTANCE,
                null,
                null
        );
        outputProcessor = new GatewayOutputProcessor(context);
    }

    @Test
    void returnsFalseWhenEndpointUnavailable() {
        when(endpointRegistry.sendMessage("worker-1", "{\"hello\":\"world\"}"))
                .thenReturn(false);

        boolean result = outputProcessor.process(
                new OutboundDelivery("worker-1", "{\"hello\":\"world\"}", "trace-1")
        );

        assertFalse(result);
    }

    @Test
    void returnsTrueWhenEndpointSendSucceeds() {
        when(endpointRegistry.sendMessage("worker-1", "{\"hello\":\"world\"}"))
                .thenReturn(true);

        boolean result = outputProcessor.process(
                new OutboundDelivery("worker-1", "{\"hello\":\"world\"}", "trace-1")
        );

        assertTrue(result);
    }
}
