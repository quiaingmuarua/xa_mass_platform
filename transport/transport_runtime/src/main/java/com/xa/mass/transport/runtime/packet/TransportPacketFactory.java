package com.xa.mass.transport.runtime.packet;

import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class TransportPacketFactory {

    private final Supplier<String> packetIdSupplier;

    public TransportPacketFactory() {
        this(() -> UUID.randomUUID().toString());
    }

    public TransportPacketFactory(Supplier<String> packetIdSupplier) {
        this.packetIdSupplier = Objects.requireNonNull(packetIdSupplier, "packetIdSupplier");
    }

    public TransportPacket workerSystemEvent(String eventCode,
                                             String adapterId,
                                             String routeKey,
                                             String traceId,
                                             Map<String, Object> payload) {
        return new TransportPacket(
                TransportPacket.CURRENT_VERSION,
                packetIdSupplier.get(),
                traceId,
                PacketType.WORKER_SYSTEM_EVENT,
                adapterId,
                routeKey,
                null,
                null,
                null,
                eventCode,
                TransportPacket.JSON_CONTENT_TYPE,
                payload == null ? Map.of() : payload
        );
    }

}
