package com.xa.mass.transport.runtime.packet;

import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TaskResultReport;
import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;

import java.util.LinkedHashMap;
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

    public TransportPacket fromDispatchItem(String adapterId,
                                            String routeKey,
                                            String traceId,
                                            TaskDispatchItem item) {
        return fromDispatchItem(packetIdSupplier.get(), adapterId, routeKey, traceId, item);
    }

    public TransportPacket fromDispatchItem(String packetId,
                                            String adapterId,
                                            String routeKey,
                                            String traceId,
                                            TaskDispatchItem item) {
        Objects.requireNonNull(item, "item");
        return new TransportPacket(
                TransportPacket.CURRENT_VERSION,
                packetId,
                traceId,
                PacketType.TASK_DISPATCH,
                adapterId,
                routeKey,
                item.getTaskId(),
                item.getMessageId(),
                item.attemptId(),
                item.getEventCode(),
                TransportPacket.JSON_CONTENT_TYPE,
                item.toTransportPayload()
        );
    }

    public TransportPacket fromResultReport(String adapterId,
                                            String routeKey,
                                            String traceId,
                                            String attemptId,
                                            TaskResultReport report) {
        Objects.requireNonNull(report, "report");
        return new TransportPacket(
                TransportPacket.CURRENT_VERSION,
                packetIdSupplier.get(),
                traceId,
                PacketType.TASK_RESULT,
                adapterId,
                routeKey,
                report.getTaskId(),
                report.getMessageId(),
                normalize(attemptId),
                null,
                TransportPacket.JSON_CONTENT_TYPE,
                resultPayload(report)
        );
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

    private static Map<String, Object> resultPayload(TaskResultReport report) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", report.isSuccess());
        put(payload, "detail", report.getDetail());
        put(payload, "errorCode", report.getErrorCode());
        payload.put("output", report.getOutput() == null ? Map.of() : report.getOutput());
        return payload;
    }

    private static void put(Map<String, Object> payload, String key, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(key, value);
        }
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

