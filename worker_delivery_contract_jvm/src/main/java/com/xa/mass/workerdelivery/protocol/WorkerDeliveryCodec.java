package com.xa.mass.workerdelivery.protocol;

import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliverSeed;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class WorkerDeliveryCodec {

    private static final Set<String> CONNECTION_MESSAGE_FIELDS = Set.of(
            "messageType",
            "payload"
    );
    private static final Set<String> CONNECTION_BIND_FIELDS = Set.of(
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
    public WorkerConnectionMessage decodeWorkerConnectionMessage(
            String value
    ) {
        try {
            Map<String, Object> payload = Jsons.parseObject(value);
            if (!payload.keySet().equals(CONNECTION_MESSAGE_FIELDS)
                    || string(payload.get("messageType")) == null
                    || string(payload.get("payload")) == null) {
                return null;
            }
            return new WorkerConnectionMessage(
                    string(payload.get("messageType")),
                    string(payload.get("payload"))
            );
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
        payload.put("messageType", message.messageType());
        payload.put("payload", message.payload());
        return Jsons.toJson(payload);
    }

    public WorkerConnectionBind decodeWorkerConnectionBind(String value) {
        try {
            Map<String, Object> payload = Jsons.parseObject(value);
            if (!payload.keySet().equals(CONNECTION_BIND_FIELDS)
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
