package com.xa.mass.workerdelivery.protocol;

import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliverSeed;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.TaskItemCommandMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.TaskItemResultMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessageType;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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

    public WorkerConnectionBind decodeWorkerConnectionBind(String value) {
        try {
            Map<String, Object> payload = Jsons.parseObject(value);
            if (!payload.keySet().equals(CONNECTION_BIND_FIELDS)
                    || !WORKER_BIND_MESSAGE_TYPE.equals(
                            string(payload.get("messageType"))
                    )
                    || string(payload.get("workerId")) == null) {
                return null;
            }
            return new WorkerConnectionBind(
                    string(payload.get("workerId"))
            );
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    public String encodeWorkerConnectionBind(WorkerConnectionBind bind) {
        if (bind == null) {
            throw new IllegalArgumentException(
                    "WorkerConnectionBind must be present"
            );
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageType", WORKER_BIND_MESSAGE_TYPE);
        payload.put("workerId", bind.workerId());
        return Jsons.toJson(payload);
    }

    public WorkerCommandEnvelope decodeWorkerCommand(String value) {
        try {
            Map<String, Object> payload = Jsons.parseObject(value);
            Long executeBefore = integralLong(
                    payload.get("executeBeforeMillis")
            );
            if (!payload.keySet().equals(COMMAND_FIELDS)
                    || string(payload.get("commandId")) == null
                    || executeBefore == null
                    || string(payload.get("messageType")) == null
                    || string(payload.get("opaqueItem")) == null) {
                return null;
            }
            return new WorkerCommandEnvelope(
                    string(payload.get("commandId")),
                    WorkerMessageType.valueOf(
                            string(payload.get("messageType"))
                    ),
                    executeBefore,
                    string(payload.get("opaqueItem"))
            );
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    public String encodeWorkerCommand(WorkerCommandEnvelope command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commandId", command.commandId());
        payload.put("executeBeforeMillis", command.executeBeforeMillis());
        payload.put("messageType", command.messageType().name());
        payload.put("opaqueItem", command.opaqueItem());
        return Jsons.toJson(payload);
    }

    public DeliverSeed decodeDeliverSeed(String value) {
        try {
            Map<String, Object> payload = Jsons.parseObject(value);
            if (!payload.keySet().equals(DELIVER_SEED_FIELDS)
                    || string(payload.get("workerId")) == null
                    || string(payload.get("opaqueDeliveryItem")) == null
                    || string(payload.get("opaqueResultContext")) == null) {
                return null;
            }
            return new DeliverSeed(
                    string(payload.get("workerId")),
                    string(payload.get("opaqueDeliveryItem")),
                    string(payload.get("opaqueResultContext"))
            );
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    public String encodeDeliverSeed(DeliverSeed seed) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("opaqueDeliveryItem", seed.opaqueDeliveryItem());
        payload.put("opaqueResultContext", seed.opaqueResultContext());
        payload.put("workerId", seed.workerId());
        return Jsons.toJson(payload);
    }

    public SeedResult decodeSeedResult(String value) {
        try {
            Map<String, Object> payload = Jsons.parseObject(value);
            if (!payload.keySet().equals(RESULT_FIELDS)
                    || string(payload.get("commandId")) == null
                    || string(payload.get("opaqueResultContext")) == null
                    || !isNullableString(
                            payload.get("opaqueResultPayload")
                    )
                    || string(payload.get("outcomeCode")) == null) {
                return null;
            }
            return new SeedResult(
                    string(payload.get("commandId")),
                    string(payload.get("opaqueResultContext")),
                    string(payload.get("outcomeCode")),
                    nullableString(payload.get("opaqueResultPayload"))
            );
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    public String encodeSeedResult(SeedResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commandId", result.commandId());
        payload.put("opaqueResultContext", result.opaqueResultContext());
        payload.put("opaqueResultPayload", result.opaqueResultPayload());
        payload.put("outcomeCode", result.outcomeCode());
        return Jsons.toJson(payload);
    }

    public WorkerConnectionMessage decodeWorkerConnectionMessage(
            String value
    ) {
        try {
            Map<String, Object> payload = Jsons.parseObject(value);
            String messageType = string(payload.get("messageType"));
            if (messageType == null) {
                return null;
            }
            WorkerConnectionMessageType type =
                    WorkerConnectionMessageType.valueOf(messageType);
            if (type == WorkerConnectionMessageType.TASK_ITEM_COMMAND) {
                return decodeTaskItemCommandMessage(payload);
            }
            return decodeTaskItemResultMessage(payload);
        } catch (IllegalArgumentException error) {
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
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageType", message.messageType().name());
        if (message instanceof TaskItemCommandMessage) {
            WorkerCommandEnvelope command =
                    ((TaskItemCommandMessage) message).command();
            payload.put("commandId", command.commandId());
            payload.put(
                    "executeBeforeMillis",
                    command.executeBeforeMillis()
            );
            payload.put("opaqueItem", command.opaqueItem());
        } else if (message instanceof TaskItemResultMessage) {
            SeedResult result = ((TaskItemResultMessage) message).result();
            payload.put("commandId", result.commandId());
            payload.put(
                    "opaqueResultContext",
                    result.opaqueResultContext()
            );
            payload.put(
                    "opaqueResultPayload",
                    result.opaqueResultPayload()
            );
            payload.put("outcomeCode", result.outcomeCode());
        } else {
            throw new IllegalArgumentException(
                    "Unsupported WorkerConnectionMessage implementation"
            );
        }
        return Jsons.toJson(payload);
    }

    private WorkerConnectionMessage decodeTaskItemCommandMessage(
            Map<String, Object> payload
    ) {
        Long executeBefore = integralLong(
                payload.get("executeBeforeMillis")
        );
        if (!payload.keySet().equals(CONNECTION_COMMAND_FIELDS)
                || string(payload.get("commandId")) == null
                || executeBefore == null
                || string(payload.get("opaqueItem")) == null) {
            return null;
        }
        return new TaskItemCommandMessage(new WorkerCommandEnvelope(
                string(payload.get("commandId")),
                WorkerMessageType.TASK_ITEM,
                executeBefore,
                string(payload.get("opaqueItem"))
        ));
    }

    private WorkerConnectionMessage decodeTaskItemResultMessage(
            Map<String, Object> payload
    ) {
        if (!payload.keySet().equals(CONNECTION_RESULT_FIELDS)
                || string(payload.get("commandId")) == null
                || string(payload.get("opaqueResultContext")) == null
                || !isNullableString(payload.get("opaqueResultPayload"))
                || string(payload.get("outcomeCode")) == null) {
            return null;
        }
        return new TaskItemResultMessage(new SeedResult(
                string(payload.get("commandId")),
                string(payload.get("opaqueResultContext")),
                string(payload.get("outcomeCode")),
                nullableString(payload.get("opaqueResultPayload"))
        ));
    }

    private static String string(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static boolean isNullableString(Object value) {
        return value == null || value instanceof String;
    }

    private static String nullableString(Object value) {
        return value == null ? null : (String) value;
    }

    private static Long integralLong(Object value) {
        if (!(value instanceof Number)) {
            return null;
        }
        try {
            return new BigDecimal(value.toString())
                    .toBigIntegerExact()
                    .longValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            return null;
        }
    }
}
