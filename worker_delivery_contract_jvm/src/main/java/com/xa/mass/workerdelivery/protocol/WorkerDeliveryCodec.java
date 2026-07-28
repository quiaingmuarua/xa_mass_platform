package com.xa.mass.workerdelivery.protocol;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliverSeed;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.TaskItemCommandMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.TaskItemResultMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessageType;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

public final class WorkerDeliveryCodec {

    private static final String WORKER_BIND_MESSAGE_TYPE = "WORKER_BIND";
    private static final Set<String> CONNECTION_BIND_FIELDS = Set.of(
            "messageType",
            "workerId"
    );
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
    private static final Set<String> CONNECTION_COMMAND_FIELDS = Set.of(
            "commandId",
            "executeBeforeMillis",
            "messageType",
            "opaqueItem"
    );
    private static final Set<String> CONNECTION_RESULT_FIELDS = Set.of(
            "commandId",
            "messageType",
            "opaqueResultContext",
            "opaqueResultPayload",
            "outcomeCode"
    );

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public WorkerConnectionBind decodeWorkerConnectionBind(String value) {
        try {
            JsonNode payload = objectMapper.readTree(value);
            if (!(payload instanceof ObjectNode object)
                    || !fieldNames(object).equals(CONNECTION_BIND_FIELDS)) {
                return null;
            }
            JsonNode messageType = object.get("messageType");
            JsonNode workerId = object.get("workerId");
            if (!messageType.isTextual()
                    || !WORKER_BIND_MESSAGE_TYPE.equals(
                            messageType.textValue()
                    )
                    || !workerId.isTextual()) {
                return null;
            }
            return new WorkerConnectionBind(workerId.textValue());
        } catch (JacksonException | IllegalArgumentException error) {
            return null;
        }
    }

    public String encodeWorkerConnectionBind(WorkerConnectionBind bind) {
        if (bind == null) {
            throw new IllegalArgumentException(
                    "WorkerConnectionBind must be present"
            );
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("messageType", WORKER_BIND_MESSAGE_TYPE);
        payload.put("workerId", bind.workerId());
        return write(payload, "WorkerConnectionBind");
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

    public WorkerConnectionMessage decodeWorkerConnectionMessage(
            String value
    ) {
        try {
            JsonNode payload = objectMapper.readTree(value);
            if (!(payload instanceof ObjectNode object)) {
                return null;
            }
            JsonNode messageType = object.get("messageType");
            if (messageType == null || !messageType.isTextual()) {
                return null;
            }
            WorkerConnectionMessageType type =
                    WorkerConnectionMessageType.valueOf(
                            messageType.textValue()
                    );
            return switch (type) {
                case TASK_ITEM_COMMAND ->
                        decodeTaskItemCommandMessage(object);
                case TASK_ITEM_RESULT ->
                        decodeTaskItemResultMessage(object);
            };
        } catch (JacksonException | IllegalArgumentException error) {
            return null;
        }
    }

    public String encodeWorkerConnectionMessage(
            WorkerConnectionMessage message
    ) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "WorkerConnectionMessage must be present"
            );
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("messageType", message.messageType().name());
        switch (message) {
            case TaskItemCommandMessage commandMessage -> {
                WorkerCommandEnvelope command = commandMessage.command();
                payload.put("commandId", command.commandId());
                payload.put(
                        "executeBeforeMillis",
                        command.executeBeforeMillis()
                );
                payload.put("opaqueItem", command.opaqueItem());
            }
            case TaskItemResultMessage resultMessage -> {
                SeedResult result = resultMessage.result();
                payload.put("commandId", result.commandId());
                payload.put(
                        "opaqueResultContext",
                        result.opaqueResultContext()
                );
                if (result.opaqueResultPayload() == null) {
                    payload.putNull("opaqueResultPayload");
                } else {
                    payload.put(
                            "opaqueResultPayload",
                            result.opaqueResultPayload()
                    );
                }
                payload.put("outcomeCode", result.outcomeCode());
            }
        }
        return write(payload, "WorkerConnectionMessage");
    }

    private WorkerConnectionMessage decodeTaskItemCommandMessage(
            ObjectNode object
    ) {
        if (!fieldNames(object).equals(CONNECTION_COMMAND_FIELDS)) {
            return null;
        }
        JsonNode commandId = object.get("commandId");
        JsonNode executeBefore = object.get("executeBeforeMillis");
        JsonNode opaqueItem = object.get("opaqueItem");
        if (!commandId.isTextual()
                || !executeBefore.isIntegralNumber()
                || !opaqueItem.isTextual()) {
            return null;
        }
        return new TaskItemCommandMessage(new WorkerCommandEnvelope(
                commandId.textValue(),
                WorkerMessageType.TASK_ITEM,
                executeBefore.longValue(),
                opaqueItem.textValue()
        ));
    }

    private WorkerConnectionMessage decodeTaskItemResultMessage(
            ObjectNode object
    ) {
        if (!fieldNames(object).equals(CONNECTION_RESULT_FIELDS)) {
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
        return new TaskItemResultMessage(new SeedResult(
                commandId.textValue(),
                resultContext.textValue(),
                outcomeCode.textValue(),
                resultPayload.isNull()
                        ? null
                        : resultPayload.textValue()
        ));
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
