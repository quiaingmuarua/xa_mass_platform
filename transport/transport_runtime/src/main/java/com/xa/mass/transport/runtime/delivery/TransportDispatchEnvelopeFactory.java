package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TransportDispatchEnvelope;

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

    public TransportDispatchEnvelopeFactory() {
        this(() -> UUID.randomUUID().toString(), System::currentTimeMillis);
    }

    public TransportDispatchEnvelopeFactory(Supplier<String> deliveryIdSupplier, LongSupplier currentTimeMillis) {
        this.deliveryIdSupplier = Objects.requireNonNull(deliveryIdSupplier, "deliveryIdSupplier");
        this.currentTimeMillis = Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
    }

    public TransportDispatchEnvelope create(String adapterId,
                                            String routeKey,
                                            String correlationKey,
                                            TaskDispatchItem payload) {
        return new TransportDispatchEnvelope(
                deliveryIdSupplier.get(),
                adapterId,
                routeKey,
                correlationKey,
                payload,
                currentTimeMillis.getAsLong()
        );
    }
}
