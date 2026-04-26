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
    void hasWorkerIdRejectsBlankValues() {
        RawWorkerMessageChannel channel = channel("socket");

        assertTrue(channel.hasWorkerId("worker-1"));
        assertFalse(channel.hasWorkerId(null));
        assertFalse(channel.hasWorkerId(" "));
    }

    private RawWorkerMessageChannel channel(String adapterId) {
        return new RawWorkerMessageChannel() {
            @Override
            public String adapterId() {
                return adapterId;
            }

            @Override
            public void send(String workerId, String rawJson, String traceId) {
            }
        };
    }
}
