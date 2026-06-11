package com.xa.mass.transport.model;

import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;

import java.util.Objects;

/**
 * Transport-owned carrier for one dispatch payload moving through runtime
 * queues, direct-send paths, and adapter dispatch channels.
 */
public final class TransportDispatchEnvelope {

    private final String deliveryId;
    private final String deliveryQueueKey;
    private final String selectedWorkerId;
    private final TransportPacket packet;
    private final long createdAtEpochMillis;

    public TransportDispatchEnvelope(String deliveryId,
                                     String deliveryQueueKey,
                                     String selectedWorkerId,
                                     TransportPacket packet,
                                     long createdAtEpochMillis) {
        this.deliveryId = requireText(deliveryId, "deliveryId");
        this.deliveryQueueKey = TransportDeliveryAddressing.normalizeText(deliveryQueueKey);
        this.selectedWorkerId = TransportDeliveryAddressing.normalizeText(selectedWorkerId);
        this.packet = requireDispatchPacket(packet);
        this.createdAtEpochMillis = createdAtEpochMillis;
    }

    public String getDeliveryId() {
        return deliveryId;
    }

    public String getDeliveryQueueKey() {
        return deliveryQueueKey;
    }

    public String getSelectedWorkerId() {
        return selectedWorkerId;
    }

    public String getAdapterId() {
        return packet.adapterId();
    }

    public String getRouteKey() {
        return packet.routeKey();
    }

    public String getAttemptId() {
        return packet.attemptId();
    }

    public String getTraceId() {
        return packet.traceId();
    }

    public TransportPacket getPacket() {
        return packet;
    }

    public long getCreatedAtEpochMillis() {
        return createdAtEpochMillis;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static TransportPacket requireDispatchPacket(TransportPacket packet) {
        Objects.requireNonNull(packet, "packet");
        if (packet.type() != PacketType.TASK_DISPATCH) {
            throw new IllegalArgumentException("TransportDispatchEnvelope requires TASK_DISPATCH packet");
        }
        return packet;
    }

}
