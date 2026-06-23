package com.xa.mass.client.worker.channel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.client.worker.WorkerAction;
import com.xa.mass.client.worker.WorkerActionReply;
import com.xa.mass.client.worker.handler.WorkerActionResult;
import com.xa.mass.contract.worker.WorkerChannelFrame;
import com.xa.mass.contract.worker.WorkerChannelFrameJsonCodec;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class WorkerChannelFrameCodec {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final WorkerChannelFrameJsonCodec frameJsonCodec;

    public WorkerChannelFrameCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.frameJsonCodec = new WorkerChannelFrameJsonCodec(this.objectMapper);
    }

    public WorkerAction decodeActionFrame(String frame) throws JsonProcessingException {
        WorkerChannelFrame channelFrame = frameJsonCodec.decode(frame);
        if (!WorkerChannelFrame.ACTION.equals(channelFrame.kind())) {
            return null;
        }
        JsonNode root = objectMapper.readTree(channelFrame.body());
        return new WorkerAction(
                text(root, "actionId"),
                text(root, "replyRef"),
                text(root, "eventCode"),
                body(root.get("body")),
                objectMap(root.get("sharedConfig"))
        );
    }

    public String encodeActionReplyFrame(String replyRef, WorkerActionResult result) throws JsonProcessingException {
        WorkerActionReply reply = new WorkerActionReply(
                replyRef,
                result.success(),
                result.code(),
                result.body()
        );
        WorkerChannelFrame frame = new WorkerChannelFrame(
                UUID.randomUUID().toString(),
                WorkerChannelFrame.ACTION_REPLY,
                objectMapper.writeValueAsString(reply)
        );
        return frameJsonCodec.encode(frame);
    }

    private Map<String, Object> objectMap(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, MAP_TYPE);
    }

    private String body(JsonNode node) throws JsonProcessingException {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.isTextual() ? node.asText() : objectMapper.writeValueAsString(node);
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }
}
