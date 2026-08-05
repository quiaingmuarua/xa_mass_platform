package com.xa.mass.workerdelivery.protocol;

import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class WorkerDeliveryCodec {

    private static final Set<String> CONNECTION_BIND_FIELDS =
            Set.of("workerId");
    private static final Set<String> COMMAND_FIELDS = Set.of(
            "dst",
            "executeBeforeMillis",
            "forward",
            "messageId",
            "messageType",
            "payload",
            "src"
    );
    private static final Set<String> RESULT_FIELDS = Set.of(
            "dst",
            "forward",
            "messageId",
            "messageType",
            "outcomeCode",
            "payload"
    );

    public WorkerConnectionBind decodeWorkerConnectionBind(String value) {
        try {
            Map<String, Object> payload = Jsons.parseObject(value);
            if (!payload.keySet().equals(CONNECTION_BIND_FIELDS)
                    || string(payload.get("workerId")) == null) {
                return null;
            }
            return new WorkerConnectionBind(string(payload.get("workerId")));
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

    public WorkerCommand decodeWorkerCommand(String value) {
        try {
            Map<String, Object> payload = Jsons.parseObject(value);
            Long executeBefore = integralLong(
                    payload.get("executeBeforeMillis")
            );
            if (!payload.keySet().equals(COMMAND_FIELDS)
                    || string(payload.get("messageId")) == null
                    || string(payload.get("src")) == null
                    || string(payload.get("dst")) == null
                    || string(payload.get("messageType")) == null
                    || executeBefore == null
                    || string(payload.get("payload")) == null
                    || string(payload.get("forward")) == null) {
                return null;
            }
            return new WorkerCommand(
                    string(payload.get("messageId")),
                    WorkerMessageEndpoint.fromWire(
                            string(payload.get("src"))
                    ),
                    WorkerMessageEndpoint.fromWire(
                            string(payload.get("dst"))
                    ),
                    string(payload.get("messageType")),
                    executeBefore,
                    string(payload.get("payload")),
                    string(payload.get("forward"))
            );
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    public String encodeWorkerCommand(WorkerCommand command) {
        if (command == null) {
            throw new IllegalArgumentException(
                    "WorkerCommand must be present"
            );
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dst", command.dst().wireValue());
        payload.put(
                "executeBeforeMillis",
                command.executeBeforeMillis()
        );
        payload.put("forward", command.forward());
        payload.put("messageId", command.messageId());
        payload.put("messageType", command.messageType());
        payload.put("payload", command.payload());
        payload.put("src", command.src().wireValue());
        return Jsons.toJson(payload);
    }

    public WorkerResult decodeWorkerResult(String value) {
        try {
            Map<String, Object> payload = Jsons.parseObject(value);
            if (!payload.keySet().equals(RESULT_FIELDS)
                    || string(payload.get("messageId")) == null
                    || string(payload.get("dst")) == null
                    || string(payload.get("messageType")) == null
                    || string(payload.get("outcomeCode")) == null
                    || string(payload.get("payload")) == null
                    || string(payload.get("forward")) == null) {
                return null;
            }
            return new WorkerResult(
                    string(payload.get("messageId")),
                    WorkerMessageEndpoint.fromWire(
                            string(payload.get("dst"))
                    ),
                    string(payload.get("messageType")),
                    string(payload.get("outcomeCode")),
                    string(payload.get("payload")),
                    string(payload.get("forward"))
            );
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    public String encodeWorkerResult(WorkerResult result) {
        if (result == null) {
            throw new IllegalArgumentException(
                    "WorkerResult must be present"
            );
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dst", result.dst().wireValue());
        payload.put("forward", result.forward());
        payload.put("messageId", result.messageId());
        payload.put("messageType", result.messageType());
        payload.put("outcomeCode", result.outcomeCode());
        payload.put("payload", result.payload());
        return Jsons.toJson(payload);
    }

    private static String string(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                return null;
            }
            result.put((String) entry.getKey(), entry.getValue());
        }
        return result;
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
