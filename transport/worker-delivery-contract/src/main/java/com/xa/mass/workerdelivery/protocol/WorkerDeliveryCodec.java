package com.xa.mass.workerdelivery.protocol;

import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class WorkerDeliveryCodec {

    private static final Set<String> COMMAND_FIELDS = Set.of(
            "dst",
            "executeBeforeMillis",
            "forward",
            "messageType",
            "payload",
            "src"
    );
    private static final Set<String> REPORT_FIELDS = Set.of(
            "dst",
            "forward",
            "messageType",
            "outcomeCode",
            "payload",
            "sourceId",
            "src"
    );

    public DeliveryCommand decodeDeliveryCommand(String value) {
        try {
            Map<String, Object> payload = Jsons.parseObject(value);
            Long executeBefore = integralLong(
                    payload.get("executeBeforeMillis")
            );
            if (!payload.keySet().equals(COMMAND_FIELDS)
                    || string(payload.get("src")) == null
                    || string(payload.get("dst")) == null
                    || string(payload.get("messageType")) == null
                    || executeBefore == null
                    || string(payload.get("payload")) == null
                    || string(payload.get("forward")) == null) {
                return null;
            }
            return DeliveryCommand.restore(
                    DeliveryEndpoint.fromWire(string(payload.get("src"))),
                    DeliveryEndpoint.fromWire(string(payload.get("dst"))),
                    string(payload.get("messageType")),
                    executeBefore,
                    string(payload.get("payload")),
                    string(payload.get("forward"))
            );
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    public String encodeDeliveryCommand(DeliveryCommand command) {
        if (command == null) {
            throw new IllegalArgumentException(
                    "DeliveryCommand must be present"
            );
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dst", command.dst().wireValue());
        payload.put("executeBeforeMillis", command.executeBeforeMillis());
        payload.put("forward", command.forward());
        payload.put("messageType", command.messageType());
        payload.put("payload", command.payload());
        payload.put("src", command.src().wireValue());
        return Jsons.toJson(payload);
    }

    public DeliveryReport decodeDeliveryReport(String value) {
        try {
            return decodeDeliveryReport(Jsons.parseObject(value));
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    public DeliveryReport decodeDeliveryReport(
            Map<String, ?> payload
    ) {
        try {
            if (payload == null) {
                return null;
            }
            if (!payload.keySet().equals(REPORT_FIELDS)
                    || string(payload.get("src")) == null
                    || string(payload.get("sourceId")) == null
                    || string(payload.get("dst")) == null
                    || string(payload.get("messageType")) == null
                    || string(payload.get("outcomeCode")) == null
                    || string(payload.get("payload")) == null
                    || string(payload.get("forward")) == null) {
                return null;
            }
            return DeliveryReport.restore(
                    DeliveryEndpoint.fromWire(string(payload.get("src"))),
                    string(payload.get("sourceId")),
                    DeliveryEndpoint.fromWire(string(payload.get("dst"))),
                    string(payload.get("messageType")),
                    string(payload.get("outcomeCode")),
                    string(payload.get("payload")),
                    string(payload.get("forward"))
            );
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    public String encodeDeliveryReport(DeliveryReport report) {
        return Jsons.toJson(encodeDeliveryReportFields(report));
    }

    public Map<String, Object> encodeDeliveryReportFields(
            DeliveryReport report
    ) {
        if (report == null) {
            throw new IllegalArgumentException(
                    "DeliveryReport must be present"
            );
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dst", report.dst().wireValue());
        payload.put("forward", report.forward());
        payload.put("messageType", report.messageType());
        payload.put("outcomeCode", report.outcomeCode());
        payload.put("payload", report.payload());
        payload.put("sourceId", report.sourceId());
        payload.put("src", report.src().wireValue());
        return Collections.unmodifiableMap(payload);
    }

    /** Captures the flat string properties wire contract in canonical key order. */
    public static Map<String, String> copyWorkerProperties(Map<?, ?> properties) {
        if (properties == null) {
            throw new IllegalArgumentException("workerProperties must be present");
        }
        Map<String, String> captured = new TreeMap<>();
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            if (!(entry.getKey() instanceof String)
                    || ((String) entry.getKey()).isBlank()
                    || !(entry.getValue() instanceof String)) {
                throw new IllegalArgumentException(
                        "workerProperties require non-blank keys and string values"
                );
            }
            captured.put((String) entry.getKey(), (String) entry.getValue());
        }
        return Collections.unmodifiableMap(captured);
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
