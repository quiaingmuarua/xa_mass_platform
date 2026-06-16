package com.xa.mass.transport.runtime.packet;

import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TransportPacketFactoryTest {

    @Test
    void workerSystemEventPacketCarriesTypeAndPayload() {
        TransportPacketFactory factory = new TransportPacketFactory(() -> "packet-event");

        TransportPacket packet = factory.workerSystemEvent(
                "worker.online",
                "polling",
                "worker-1",
                "trace-3",
                Map.of(TransportPacket.PAYLOAD_WORKER_ID, "worker-1", "reason", "connected")
        );

        assertEquals(PacketType.WORKER_SYSTEM_EVENT, packet.type());
        assertEquals("polling", packet.adapterId());
        assertEquals("worker-1", packet.routeKey());
        assertEquals("worker.online", packet.eventCode());
        assertEquals("connected", assertInstanceOf(Map.class, packet.payload()).get("reason"));
    }
}
