package com.xa.mass.server.workerdelivery.protocol;

import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public final class WorkerDeliveryCodec {

    private static final Set<String> COMMAND_FIELDS = Set.of(
            "commandId",
            "executeBeforeMillis",
            "messageType",
            "opaqueItem"
    );

    private final ObjectMapper objectMapper;

    public WorkerDeliveryCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public WorkerCommandEnvelope decodeWorkerCommand(String value) {
        try {
            JsonNode payload = objectMapper.readTree(value);
            if (!(payload instanceof ObjectNode object)
                    || !fieldNames(object).equals(COMMAND_FIELDS)) {
                return null;
            }
            JsonNode commandId = object.get("commandId");
            JsonNode executeBefore = object.get("executeBeforeMillis");
            JsonNode messageType = object.get("messageType");
            JsonNode opaqueItem = object.get("opaqueItem");
            if (!commandId.isTextual()
                    || !executeBefore.isIntegralNumber()
                    || !messageType.isTextual()
                    || !opaqueItem.isTextual()) {
                return null;
            }
            return new WorkerCommandEnvelope(
                    commandId.textValue(),
                    WorkerMessageType.valueOf(messageType.textValue()),
                    executeBefore.longValue(),
                    opaqueItem.textValue()
            );
        } catch (JacksonException | IllegalArgumentException error) {
            return null;
        }
    }

    public String encodeSeedResult(SeedResult result) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("commandId", result.commandId());
        payload.put("opaqueResultContext", result.opaqueResultContext());
        if (result.opaqueResultPayload() == null) {
            payload.putNull("opaqueResultPayload");
        } else {
            payload.put("opaqueResultPayload", result.opaqueResultPayload());
        }
        payload.put("outcomeCode", result.outcomeCode());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException error) {
            throw new IllegalStateException(
                    "Could not encode SeedResult",
                    error
            );
        }
    }

    private static Set<String> fieldNames(ObjectNode object) {
        return new java.util.HashSet<>(object.propertyNames());
    }
}
