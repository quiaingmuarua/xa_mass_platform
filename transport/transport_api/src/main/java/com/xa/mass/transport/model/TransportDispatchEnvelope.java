package com.xa.mass.transport.model;

import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;
import com.xa.mass.transport.packet.TransportPacketViews;

import java.util.Objects;

/**
 * Transport-owned carrier for one dispatch payload moving through runtime
 * queues, direct-send paths, and adapter dispatch channels.
 */
public final class TransportDispatchEnvelope {

    private final String deliveryId;
    private final TransportPacket packet;
    private final TaskDispatchItem payload;
    private final long createdAtEpochMillis;

    public TransportDispatchEnvelope(String deliveryId,
                                     TransportPacket packet,
                                     long createdAtEpochMillis) {
        this(deliveryId, packet, TransportPacketViews.toTaskDispatchItem(packet), createdAtEpochMillis);
    }

    public TransportDispatchEnvelope(String deliveryId,
                                     TransportPacket packet,
                                     TaskDispatchItem payload,
                                     long createdAtEpochMillis) {
        this.deliveryId = requireText(deliveryId, "deliveryId");
        this.packet = requireDispatchPacket(packet);
        this.payload = Objects.requireNonNull(payload, "payload");
        this.createdAtEpochMillis = createdAtEpochMillis;
    }

    public TransportDispatchEnvelope(String deliveryId,
                                     String adapterId,
                                     String routeKey,
                                     String correlationKey,
                                     TaskDispatchItem payload,
                                     long createdAtEpochMillis) {
        this(
                deliveryId,
                new TransportPacket(
                        TransportPacket.CURRENT_VERSION,
                        requireText(deliveryId, "deliveryId"),
                        TransportDeliveryAddressing.normalizeText(correlationKey),
                        PacketType.TASK_DISPATCH,
                        adapterId,
                        routeKey,
                        payload == null ? null : payload.getTaskId(),
                        payload == null ? null : payload.getMessageId(),
                        payload == null ? null : payload.attemptId(),
                        payload == null ? null : payload.getEventCode(),
                        TransportPacket.JSON_CONTENT_TYPE,
                        payload == null ? null : TransportPacketViews.dispatchPayload(payload.wireView())
                ),
                Objects.requireNonNull(payload, "payload"),
                createdAtEpochMillis
        );
    }

    public String getDeliveryId() {
        return deliveryId;
    }

    public String getAdapterId() {
        return packet.adapterId();
    }

    public String getRouteKey() {
        return packet.routeKey();
    }

    public String getCorrelationKey() {
        return packet.traceId();
    }

    public TaskDispatchItem getPayload() {
        return payload;
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
