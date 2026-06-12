package com.xa.mass.transport.runtime.packet;

import com.xa.mass.transport.model.TaskDispatchContent;
import com.xa.mass.transport.model.TaskDispatchExecutionContext;
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

    public TransportPacket fromDispatchContent(String packetId,
                                               String adapterId,
                                               String routeKey,
                                               String traceId,
                                               String selectedWorkerId,
                                               TaskDispatchContent content,
                                               TaskDispatchExecutionContext executionContext) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(executionContext, "executionContext");
        return new TransportPacket(
                TransportPacket.CURRENT_VERSION,
                packetId,
                traceId,
                PacketType.TASK_DISPATCH,
                adapterId,
                routeKey,
                content.taskId(),
                content.messageId(),
                executionContext.attemptId(),
                content.eventCode(),
                TransportPacket.JSON_CONTENT_TYPE,
                dispatchPayload(selectedWorkerId, content, executionContext)
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

    private static Map<String, Object> dispatchPayload(String selectedWorkerId,
                                                       TaskDispatchContent content,
                                                       TaskDispatchExecutionContext executionContext) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, TransportPacket.PAYLOAD_TASK_NAME, executionContext.taskName());
        put(payload, TransportPacket.PAYLOAD_PROJECT, executionContext.project());
        put(payload, TransportPacket.PAYLOAD_USER_ID, executionContext.userId());
        payload.put(TransportPacket.PAYLOAD_ATTEMPT_NO, executionContext.attemptNo());
        payload.put(TransportPacket.PAYLOAD_RETRY_COUNT, executionContext.retryCount());
        put(payload, TransportPacket.PAYLOAD_WORKER_ID, selectedWorkerId);
        put(payload, TransportPacket.PAYLOAD_BATCH_ID, executionContext.batchId());
        payload.put(TransportPacket.PAYLOAD_INPUT, content.input());
        payload.put(TransportPacket.PAYLOAD_SHARED_CONFIG, content.sharedConfig());
        return Map.copyOf(payload);
    }

    private static void put(Map<String, Object> payload, String key, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(key, value.trim());
        }
    }
}
