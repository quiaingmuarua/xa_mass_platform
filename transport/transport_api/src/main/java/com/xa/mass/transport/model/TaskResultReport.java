package com.xa.mass.transport.model;

import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;
import com.xa.mass.transport.payload.TransportJsonValueNormalizer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Transport-neutral task execution result reported by a worker.
 *
 * <p>{@code output} is a JSON-object payload boundary. Values must remain
 * JSON-safe so result reports can round-trip through non-memory transport
 * queues and codecs without relying on JVM-local object shapes.</p>
 */
public final class TaskResultReport {

    private final String taskId;
    private final String messageId;
    private final boolean success;
    private final String detail;
    private final String errorCode;
    private final Map<String, Object> output;
    private final Map<String, Object> transportPayload;

    public TaskResultReport(String taskId,
                            String messageId,
                            boolean success,
                            String detail,
                            String errorCode,
                            Map<String, Object> output) {
        this(taskId, messageId, success, detail, errorCode, output, null, false);
    }

    private TaskResultReport(String taskId,
                             String messageId,
                             boolean success,
                             String detail,
                             String errorCode,
                             Map<String, Object> output,
                             Map<String, Object> transportPayload,
                             boolean trustedImmutableOutput) {
        this.taskId = taskId;
        this.messageId = messageId;
        this.success = success;
        this.detail = detail;
        this.errorCode = errorCode;
        this.output = trustedImmutableOutput ? trustedMap(output) : immutableCopy(output);
        this.transportPayload = transportPayload != null
                ? trustedMap(transportPayload)
                : buildTransportPayload(success, detail, errorCode, this.output);
    }

    public String getTaskId() {
        return taskId;
    }

    public String getMessageId() {
        return messageId;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getDetail() {
        return detail;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getOutput() {
        return output;
    }

    public Map<String, Object> toTransportPayload() {
        return transportPayload;
    }

    public static TaskResultReport fromTransportPacket(TransportPacket packet) {
        requireResultPacket(packet);
        Map<String, Object> payload = packet.payload();
        return new TaskResultReport(
                packet.taskId(),
                packet.messageId(),
                packet.payloadBoolean(TransportPacket.PAYLOAD_SUCCESS),
                packet.payloadString(TransportPacket.PAYLOAD_DETAIL),
                packet.payloadString(TransportPacket.PAYLOAD_ERROR_CODE),
                packet.payloadObject(TransportPacket.PAYLOAD_OUTPUT),
                payload,
                true
        );
    }

    public static TaskResultReport fromDecodedTransportPayload(String taskId,
                                                               String messageId,
                                                               boolean success,
                                                               String detail,
                                                               String errorCode,
                                                               Map<String, Object> output) {
        return new TaskResultReport(
                taskId,
                messageId,
                success,
                detail,
                errorCode,
                output,
                null,
                true
        );
    }

    private static Map<String, Object> buildTransportPayload(boolean success,
                                                             String detail,
                                                             String errorCode,
                                                             Map<String, Object> output) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TransportPacket.PAYLOAD_SUCCESS, success);
        put(payload, TransportPacket.PAYLOAD_DETAIL, detail);
        put(payload, TransportPacket.PAYLOAD_ERROR_CODE, errorCode);
        payload.put(TransportPacket.PAYLOAD_OUTPUT, output);
        return Map.copyOf(payload);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> values) {
        return TransportJsonValueNormalizer.normalizeObject(values, TransportPacket.PAYLOAD_OUTPUT);
    }

    private static Map<String, Object> trustedMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return values;
    }

    private static void requireResultPacket(TransportPacket packet) {
        if (packet == null) {
            throw new IllegalArgumentException("packet must not be null");
        }
        if (packet.type() != PacketType.TASK_RESULT) {
            throw new IllegalArgumentException("packet must be TASK_RESULT");
        }
    }

    private static void put(Map<String, Object> payload, String key, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(key, value);
        }
    }

}
