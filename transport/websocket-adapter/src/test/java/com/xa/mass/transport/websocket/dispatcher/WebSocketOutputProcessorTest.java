package com.xa.mass.transport.websocket.dispatcher;

import com.xa.mass.transport.RawWorkerRouteEndpointRegistry;
import com.xa.mass.transport.model.TransportOutboundMessage;
import com.xa.mass.transport.websocket.frame.WebSocketJsonFrameParser;
import com.xa.mass.transport.websocket.frame.WebSocketResultIngressFrameReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebSocketOutputProcessorTest {

    private RawWorkerRouteEndpointRegistry rawRouteEndpointRegistry;
    private WebSocketOutputProcessor outputProcessor;

    @BeforeEach
    void setUp() {
        rawRouteEndpointRegistry = mock(RawWorkerRouteEndpointRegistry.class);
        WebSocketJsonFrameParser frameParser = new WebSocketJsonFrameParser();
        WebSocketDispatcherContext context = new WebSocketDispatcherContext(
                "websocket",
                rawRouteEndpointRegistry,
                frameParser,
                new WebSocketResultIngressFrameReader("websocket", frameParser),
                null
        );
        outputProcessor = new WebSocketOutputProcessor(context);
    }

    @Test
    void returnsFalseWhenEndpointUnavailable() {
        when(rawRouteEndpointRegistry.sendToAdapterRoute("websocket", "worker-1", "{\"hello\":\"world\"}"))
                .thenReturn(false);

        boolean result = outputProcessor.process(
                new TransportOutboundMessage("worker-1", "{\"hello\":\"world\"}", "trace-1")
        );

        assertFalse(result);
    }

    @Test
    void returnsTrueWhenEndpointSendSucceeds() {
        when(rawRouteEndpointRegistry.sendToAdapterRoute("websocket", "worker-1", "{\"hello\":\"world\"}"))
                .thenReturn(true);

        boolean result = outputProcessor.process(
                new TransportOutboundMessage("worker-1", "{\"hello\":\"world\"}", "trace-1")
        );

        assertTrue(result);
    }
}

