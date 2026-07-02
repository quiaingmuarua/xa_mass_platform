package com.xa.mass.task.runtime.redis;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.xa.mass.task.runtime.BacklogFrameV1;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

final class TaskRuntimeRedisFrameCodecV1 {

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    String encodeBacklogFrame(String taskId, BacklogFrameV1 frame, long enqueuedAtMillis) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("schemaVersion", 1);
        payload.put("frameType", "RAW");
        payload.put("taskId", taskId);
        payload.put("messageId", frame.messageId());
        payload.put("eventCode", frame.eventCode());
        payload.put("retryCount", 0);
        payload.put("payloadJson", frame.payloadJson());
        payload.put("payloadRef", frame.payloadRef());
        payload.put("createdAtMillis", Math.max(0L, enqueuedAtMillis));
        payload.put("enqueuedAtMillis", Math.max(0L, enqueuedAtMillis));
        return GSON.toJson(payload);
    }

    Map<String, Object> decodeFrame(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return Map.of();
        }
        Map<String, Object> decoded = GSON.fromJson(encoded, MAP_TYPE);
        return decoded == null ? Map.of() : decoded;
    }
}
