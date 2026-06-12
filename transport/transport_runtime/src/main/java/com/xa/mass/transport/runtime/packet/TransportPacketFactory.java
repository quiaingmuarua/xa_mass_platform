package com.xa.mass.transport.runtime.packet;

import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TaskDispatchContent;
import com.xa.mass.transport.model.TaskDispatchExecutionContext;
import com.xa.mass.transport.model.TaskResultReport;
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

    public TransportPacket fromDispatchView(String adapterId,
                                            String routeKey,
                                            String traceId,
                                            TaskDispatchItem dispatchView) {
        return fromDispatchView(packetIdSupplier.get(), adapterId, routeKey, traceId, dispatchView);
    }

    public TransportPacket fromDispatchView(String packetId,
                                            String adapterId,
                                            String routeKey,
                                            String traceId,
                                            TaskDispatchItem dispatchView) {
        Objects.requireNonNull(dispatchView, "dispatchView");
        return new TransportPacket(
                TransportPacket.CURRENT_VERSION,
                packetId,
                traceId,
                PacketType.TASK_DISPATCH,
                adapterId,
                routeKey,
                dispatchView.getTaskId(),
                dispatchView.getMessageId(),
                dispatchView.attemptId(),
                dispatchView.getEventCode(),
                TransportPacket.JSON_CONTENT_TYPE,
                dispatchView.transportPayloadView()
        );
    }

    public TransportPacket fromDispatchContent(String packetId,
                                               String adapterId,
                                               String routeKey,
                                               String traceId,
                                               String selectedWorkerId,
                                               TaskDispatchContent content,
                                               TaskDispatchExecutionContext executionContext) {
        TaskDispatchItem dispatchView = TaskDispatchItem.fromAssignedDelivery(
                routeKey,
                selectedWorkerId,
                content,
                executionContext
        );
        return fromDispatchView(packetId, adapterId, routeKey, traceId, dispatchView);
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
                report.transportPayloadView()
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

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

