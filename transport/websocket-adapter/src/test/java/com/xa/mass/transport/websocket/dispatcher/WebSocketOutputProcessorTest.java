package com.xa.mass.transport.websocket.dispatcher;

import com.xa.mass.transport.websocket.queue.WebSocketTransportFrameCodec;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.NoopWorkerSystemEventChannel;
import com.xa.mass.transport.model.WorkerTransportMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebSocketOutputProcessorTest {

    private WorkerEndpointRegistry endpointRegistry;
    private WebSocketOutputProcessor outputProcessor;

    @BeforeEach
    void setUp() {
        endpointRegistry = mock(WorkerEndpointRegistry.class);
        WebSocketDispatcherContext context = new WebSocketDispatcherContext(
                endpointRegistry,
                new WebSocketTransportFrameCodec(),
                null,
                NoopWorkerSystemEventChannel.INSTANCE
        );
        outputProcessor = new WebSocketOutputProcessor(context);
    }

    @Test
    void returnsFalseWhenEndpointUnavailable() {
        when(endpointRegistry.sendMessage("worker-1", "{\"hello\":\"world\"}"))
                .thenReturn(false);

        boolean result = outputProcessor.process(
                new WorkerTransportMessage("worker-1", "{\"hello\":\"world\"}", "trace-1")
        );

        assertFalse(result);
    }

    @Test
    void returnsTrueWhenEndpointSendSucceeds() {
        when(endpointRegistry.sendMessage("worker-1", "{\"hello\":\"world\"}"))
                .thenReturn(true);

        boolean result = outputProcessor.process(
                new WorkerTransportMessage("worker-1", "{\"hello\":\"world\"}", "trace-1")
        );

        assertTrue(result);
    }
}
