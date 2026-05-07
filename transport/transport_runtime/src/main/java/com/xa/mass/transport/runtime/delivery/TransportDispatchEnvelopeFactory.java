package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.packet.TransportPacket;
import com.xa.mass.transport.runtime.packet.TransportPacketFactory;

import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Runtime-owned factory for dispatch envelopes.
 *
 * <p>The envelope remains a transport semantic carrier, but runtime-only
 * mechanics such as delivery id generation and clock reads belong in the
 * runtime assembly layer rather than in the transport API model.</p>
 */
public final class TransportDispatchEnvelopeFactory {

    private final Supplier<String> deliveryIdSupplier;
    private final LongSupplier currentTimeMillis;
    private final TransportPacketFactory packetFactory;

    public TransportDispatchEnvelopeFactory() {
        this(() -> UUID.randomUUID().toString(), System::currentTimeMillis, new TransportPacketFactory());
    }

    public TransportDispatchEnvelopeFactory(Supplier<String> deliveryIdSupplier, LongSupplier currentTimeMillis) {
        this(deliveryIdSupplier, currentTimeMillis, new TransportPacketFactory());
    }

    public TransportDispatchEnvelopeFactory(Supplier<String> deliveryIdSupplier,
                                            LongSupplier currentTimeMillis,
                                            TransportPacketFactory packetFactory) {
        this.deliveryIdSupplier = Objects.requireNonNull(deliveryIdSupplier, "deliveryIdSupplier");
        this.currentTimeMillis = Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
        this.packetFactory = Objects.requireNonNull(packetFactory, "packetFactory");
    }

    public TransportDispatchEnvelope create(String adapterId,
                                            String routeKey,
                                            String traceId,
                                            TaskDispatchItem payload) {
        String deliveryId = deliveryIdSupplier.get();
        TransportPacket packet = packetFactory.fromDispatchItem(
                deliveryId,
                adapterId,
                routeKey,
                traceId,
                payload
        );
        return new TransportDispatchEnvelope(
                deliveryId,
                packet,
                currentTimeMillis.getAsLong()
        );
    }
}
