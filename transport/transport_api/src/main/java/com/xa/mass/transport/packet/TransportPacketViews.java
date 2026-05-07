package com.xa.mass.transport.packet;

import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TaskDispatchWireView;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TransportPacketViews {

    private static final String TASK_NAME = "taskName";
    private static final String PROJECT = "project";
    private static final String USER_ID = "userId";
    private static final String RETRY_COUNT = "retryCount";
    private static final String WORKER_ID = "workerId";
    private static final String WORKER_CONTEXT_ID = "workerContextId";
    private static final String BATCH_ID = "batchId";
    private static final String INPUT = "input";
    private static final String SHARED_CONFIG = "sharedConfig";

    private TransportPacketViews() {
    }

    public static Map<String, Object> dispatchPayload(TaskDispatchWireView view) {
        if (view == null) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, TASK_NAME, view.taskName());
        put(payload, PROJECT, view.project());
        put(payload, USER_ID, view.userId());
        payload.put(RETRY_COUNT, view.retryCount());
        put(payload, WORKER_ID, view.workerId());
        put(payload, WORKER_CONTEXT_ID, view.workerContextId());
        put(payload, BATCH_ID, view.batchId());
        payload.put(INPUT, view.input() == null ? Map.of() : view.input());
        payload.put(SHARED_CONFIG, view.sharedConfig() == null ? Map.of() : view.sharedConfig());
        return payload;
    }

    public static TaskDispatchWireView dispatchWireView(TransportPacket packet) {
        requireDispatchPacket(packet);
        Map<String, Object> payload = packet.payload();
        return new TaskDispatchWireView(
                packet.taskId(),
                packet.messageId(),
                packet.eventCode(),
                stringValue(payload.get(TASK_NAME)),
                stringValue(payload.get(PROJECT)),
                stringValue(payload.get(USER_ID)),
                intValue(payload.get(RETRY_COUNT)),
                stringValue(payload.get(WORKER_ID)),
                stringValue(payload.get(WORKER_CONTEXT_ID)),
                stringValue(payload.get(BATCH_ID)),
                mapValue(payload.get(INPUT)),
                mapValue(payload.get(SHARED_CONFIG))
        );
    }

    public static TaskDispatchItem toTaskDispatchItem(TransportPacket packet) {
        requireDispatchPacket(packet);
        Map<String, Object> payload = packet.payload();
        return new TaskDispatchItem(
                packet.taskId(),
                packet.messageId(),
                packet.eventCode(),
                stringValue(payload.get(TASK_NAME)),
                stringValue(payload.get(PROJECT)),
                stringValue(payload.get(USER_ID)),
                intValue(payload.get(RETRY_COUNT)),
                packet.attemptId(),
                stringValue(payload.get(WORKER_ID)),
                stringValue(payload.get(WORKER_CONTEXT_ID)),
                stringValue(payload.get(BATCH_ID)),
                mapValue(payload.get(INPUT)),
                mapValue(payload.get(SHARED_CONFIG))
        );
    }

    private static void requireDispatchPacket(TransportPacket packet) {
        if (packet == null) {
            throw new IllegalArgumentException("packet must not be null");
        }
        if (packet.type() != PacketType.TASK_DISPATCH) {
            throw new IllegalArgumentException("packet must be TASK_DISPATCH");
        }
    }

    private static void put(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static String stringValue(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text.trim();
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        return immutableMap((Map<String, Object>) map);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(source));
    }
}
