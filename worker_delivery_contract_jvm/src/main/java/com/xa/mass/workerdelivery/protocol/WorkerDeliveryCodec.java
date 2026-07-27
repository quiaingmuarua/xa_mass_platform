package com.xa.mass.workerdelivery.protocol;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliverSeed;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

public final class WorkerDeliveryCodec {

    private static final Set<String> COMMAND_FIELDS = Set.of(
            "commandId",
            "executeBeforeMillis",
            "messageType",
            "opaqueItem"
    );
    private static final Set<String> DELIVER_SEED_FIELDS = Set.of(
            "opaqueDeliveryItem",
            "opaqueResultContext",
            "workerId"
    );
    private static final Set<String> RESULT_FIELDS = Set.of(
            "commandId",
            "opaqueResultContext",
            "opaqueResultPayload",
            "outcomeCode"
    );

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

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

    public String encodeWorkerCommand(WorkerCommandEnvelope command) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("commandId", command.commandId());
        payload.put("executeBeforeMillis", command.executeBeforeMillis());
        payload.put("messageType", command.messageType().name());
        payload.put("opaqueItem", command.opaqueItem());
        return write(payload, "WorkerCommand");
    }

    public DeliverSeed decodeDeliverSeed(String value) {
        try {
            JsonNode payload = objectMapper.readTree(value);
            if (!(payload instanceof ObjectNode object)
                    || !fieldNames(object).equals(DELIVER_SEED_FIELDS)) {
                return null;
            }
            JsonNode workerId = object.get("workerId");
            JsonNode deliveryItem = object.get("opaqueDeliveryItem");
            JsonNode resultContext = object.get("opaqueResultContext");
            if (!workerId.isTextual()
                    || !deliveryItem.isTextual()
                    || !resultContext.isTextual()) {
                return null;
            }
            return new DeliverSeed(
                    workerId.textValue(),
                    deliveryItem.textValue(),
                    resultContext.textValue()
            );
        } catch (JacksonException | IllegalArgumentException error) {
            return null;
        }
    }

    public String encodeDeliverSeed(DeliverSeed seed) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("opaqueDeliveryItem", seed.opaqueDeliveryItem());
        payload.put("opaqueResultContext", seed.opaqueResultContext());
        payload.put("workerId", seed.workerId());
        return write(payload, "DeliverSeed");
    }

    public SeedResult decodeSeedResult(String value) {
        try {
            JsonNode payload = objectMapper.readTree(value);
            if (!(payload instanceof ObjectNode object)
                    || !fieldNames(object).equals(RESULT_FIELDS)) {
                return null;
            }
            JsonNode commandId = object.get("commandId");
            JsonNode resultContext = object.get("opaqueResultContext");
            JsonNode resultPayload = object.get("opaqueResultPayload");
            JsonNode outcomeCode = object.get("outcomeCode");
            if (!commandId.isTextual()
                    || !resultContext.isTextual()
                    || !(resultPayload.isNull() || resultPayload.isTextual())
                    || !outcomeCode.isTextual()) {
                return null;
            }
            return new SeedResult(
                    commandId.textValue(),
                    resultContext.textValue(),
                    outcomeCode.textValue(),
                    resultPayload.isNull()
                            ? null
                            : resultPayload.textValue()
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
        return write(payload, "SeedResult");
    }

    private String write(ObjectNode payload, String type) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException error) {
            throw new IllegalStateException("Could not encode " + type, error);
        }
    }

    private static Set<String> fieldNames(ObjectNode object) {
        return new HashSet<>(object.propertyNames());
    }
}
