package com.xa.mass.contract.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.UUID;

/**
 * JSON codec for the public worker-channel frame only.
 */
public final class WorkerChannelFrameJsonCodec {

    private final ObjectMapper objectMapper;

    public WorkerChannelFrameJsonCodec() {
        this(new ObjectMapper().findAndRegisterModules());
    }

    public WorkerChannelFrameJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public String encode(WorkerChannelFrame frame) {
        try {
            return objectMapper.writeValueAsString(Objects.requireNonNull(frame, "frame"));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("worker channel frame cannot be encoded", ex);
        }
    }

    public WorkerChannelFrame decode(String json) {
        try {
            return objectMapper.readValue(requireText(json, "json"), WorkerChannelFrame.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("worker channel frame cannot be decoded", ex);
        }
    }

    public String encodeGenerated(String kind, String body) {
        return encode(new WorkerChannelFrame(UUID.randomUUID().toString(), kind, body));
    }

    public String encodeAction(String body) {
        return encodeGenerated(WorkerChannelFrame.ACTION, body);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }
}
