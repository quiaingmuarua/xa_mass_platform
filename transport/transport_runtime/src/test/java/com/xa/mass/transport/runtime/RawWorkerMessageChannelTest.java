package com.xa.mass.transport.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawWorkerMessageChannelTest {

    @Test
    void supportsAdapterNormalizesRequestedAdapterId() {
        RawWorkerMessageChannel channel = channel("websocket");

        assertTrue(channel.supportsAdapter(" WebSocket "));
        assertFalse(channel.supportsAdapter("socket"));
        assertFalse(channel.supportsAdapter(null));
    }

    @Test
    void hasRouteKeyRejectsBlankValues() {
        RawWorkerMessageChannel channel = channel("socket");

        assertTrue(channel.hasRouteKey("worker-1"));
        assertFalse(channel.hasRouteKey(null));
        assertFalse(channel.hasRouteKey(" "));
    }

    private RawWorkerMessageChannel channel(String adapterId) {
        return new RawWorkerMessageChannel() {
            @Override
            public String adapterId() {
                return adapterId;
            }

            @Override
            public void sendToRoute(String routeKey, String rawJson, String traceId) {
            }
        };
    }
}
