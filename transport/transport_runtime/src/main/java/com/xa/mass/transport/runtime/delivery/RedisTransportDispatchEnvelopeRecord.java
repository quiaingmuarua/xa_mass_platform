package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.packet.TransportPacket;

record RedisTransportDispatchEnvelopeRecord(String deliveryId,
                                            long createdAtEpochMillis,
                                            TransportPacket packet) {
}
