package com.xa.mass.starter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.xa.mass.sdk.worker.WorkerResultSubmitRequest;
import com.xa.mass.transport.model.TransportResultIngressEnvelope;
import com.xa.mass.transport.packet.TransportPacket;
import com.xa.mass.transport.payload.TransportJsonValueNormalizer;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.Objects;

/**
 * Starter-owned codec between worker result callback shape and opaque transport
 * ingress values.
 */
public final class TaskResultCallbackCodec {

    private static final String TASK_ID_FIELD = "taskId";
    private static final String MESSAGE_ID_FIELD = "messageId";
    private static final String MESSAGE_FIELD = "message";
    private static final String ATTEMPT_ID_FIELD = "attemptId";
    private static final String LEASE_TOKEN_FIELD = "leaseToken";
    private static final String TRACE_ID_FIELD = "traceId";
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private final Gson gson;

    public TaskResultCallbackCodec() {
        this(new GsonBuilder().create());
    }

    TaskResultCallbackCodec(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    public TransportResultIngressEnvelope toEnvelope(WorkerResultSubmitRequest request,
                                                     String partitionKey,
                                                     Map<String, String> diagnostics) {
        Objects.requireNonNull(request, "request");
        TaskResultCallbackCommand command = new TaskResultCallbackCommand(
                request.taskId(),
                request.messageId(),
                request.success(),
                request.detail(),
                request.errorCode(),
                request.output(),
                request.attemptId(),
                request.leaseToken(),
                request.traceId()
        );
        return toEnvelope(command, partitionKey, diagnostics);
    }

    public TransportResultIngressEnvelope toEnvelope(TaskResultCallbackCommand command,
                                                     String partitionKey,
                                                     Map<String, String> diagnostics) {
        Objects.requireNonNull(command, "command");
        return TransportResultIngressEnvelope.received(
                encodePayload(command),
                encodeCorrelation(command),
                partitionKey,
                diagnostics
        );
    }

    public TaskResultCallbackCommand decode(TransportResultIngressEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        TaskResultCallbackCommand payload = decodePayload(envelope.getPayload());
        CorrelationRecord correlation = decodeCorrelation(envelope.getCorrelation());
        String traceId = firstNonBlank(correlation.traceId(), envelope.diagnostic(TRACE_ID_FIELD));
        return new TaskResultCallbackCommand(
                payload.taskId(),
                payload.messageId(),
                payload.success(),
                payload.detail(),
                payload.errorCode(),
                payload.output(),
                correlation.attemptId(),
                correlation.leaseToken(),
                traceId
        );
    }

    private String encodePayload(TaskResultCallbackCommand command) {
        PayloadRecord payload = new PayloadRecord(
                command.taskId(),
                command.messageId(),
                command.success(),
                command.detail(),
                command.errorCode(),
                command.output()
        );
        return gson.toJson(payload);
    }

    private String encodeCorrelation(TaskResultCallbackCommand command) {
        if (command.attemptId() == null && command.leaseToken() == null && command.traceId() == null) {
            return null;
        }
        return gson.toJson(new CorrelationRecord(command.attemptId(), command.leaseToken(), command.traceId()));
    }

    private TaskResultCallbackCommand decodePayload(String payloadJson) {
        JsonObject payload = parseObject(payloadJson, "payload");
        String taskId = readString(payload, TASK_ID_FIELD);
        String messageId = readString(payload, MESSAGE_ID_FIELD);
        Boolean success = readBoolean(payload, TransportPacket.PAYLOAD_SUCCESS);
        if (taskId == null || messageId == null || success == null) {
            throw new IllegalArgumentException("result callback payload requires taskId, messageId, and success");
        }
        String detail = firstNonBlank(
                readString(payload, TransportPacket.PAYLOAD_DETAIL),
                readString(payload, MESSAGE_FIELD)
        );
        String errorCode = readString(payload, TransportPacket.PAYLOAD_ERROR_CODE);
        Map<String, Object> output = readObject(payload, TransportPacket.PAYLOAD_OUTPUT);
        return new TaskResultCallbackCommand(taskId, messageId, success, detail, errorCode, output, null, null, null);
    }

    private CorrelationRecord decodeCorrelation(String correlationJson) {
        if (correlationJson == null || correlationJson.isBlank()) {
            return CorrelationRecord.EMPTY;
        }
        JsonObject correlation = parseObject(correlationJson, "correlation");
        return new CorrelationRecord(
                readString(correlation, ATTEMPT_ID_FIELD),
                readString(correlation, LEASE_TOKEN_FIELD),
                readString(correlation, TRACE_ID_FIELD)
        );
    }

    private JsonObject parseObject(String json, String fieldName) {
        try {
            JsonElement element = gson.fromJson(json, JsonElement.class);
            if (element != null && element.isJsonObject()) {
                return element.getAsJsonObject();
            }
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(fieldName + " must be a JSON object", ex);
        }
        throw new IllegalArgumentException(fieldName + " must be a JSON object");
    }

    private Map<String, Object> readObject(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return Map.of();
        }
        JsonElement element = object.get(field);
        if (!element.isJsonObject()) {
            return Map.of();
        }
        Map<String, Object> decoded = gson.fromJson(element, MAP_TYPE);
        return TransportJsonValueNormalizer.freezeDecodedObject(decoded);
    }

    private Boolean readBoolean(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        try {
            return object.get(field).getAsBoolean();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String readString(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        try {
            String value = object.get(field).getAsString();
            return value == null || value.isBlank() ? null : value.trim();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private record PayloadRecord(String taskId,
                                 String messageId,
                                 boolean success,
                                 String detail,
                                 String errorCode,
                                 Map<String, Object> output) {
    }

    private record CorrelationRecord(String attemptId,
                                     String leaseToken,
                                     String traceId) {
        private static final CorrelationRecord EMPTY = new CorrelationRecord(null, null, null);
    }
}
