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

    private static final String RESULT_CORRELATION_REF_FIELD = "resultCorrelationRef";
    private static final String MESSAGE_FIELD = "message";
    private static final String ATTEMPT_ID_FIELD = "attemptId";
    private static final String LEASE_TOKEN_FIELD = "leaseToken";
    private static final String TRACE_ID_FIELD = "traceId";
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private final Gson gson;
    private final TaskDispatchDeliveryCorrelationCodec deliveryCorrelationCodec;

    public TaskResultCallbackCodec() {
        this(new GsonBuilder().create(), new TaskDispatchDeliveryCorrelationCodec());
    }

    TaskResultCallbackCodec(Gson gson) {
        this(gson, new TaskDispatchDeliveryCorrelationCodec());
    }

    TaskResultCallbackCodec(Gson gson, TaskDispatchDeliveryCorrelationCodec deliveryCorrelationCodec) {
        this.gson = Objects.requireNonNull(gson, "gson");
        this.deliveryCorrelationCodec = Objects.requireNonNull(deliveryCorrelationCodec, "deliveryCorrelationCodec");
    }

    public TransportResultIngressEnvelope toEnvelope(WorkerResultSubmitRequest request,
                                                     String partitionKey,
                                                     Map<String, String> diagnostics) {
        Objects.requireNonNull(request, "request");
        return TransportResultIngressEnvelope.received(
                encodeWorkerResultRequestPayload(request),
                null,
                partitionKey,
                diagnostics
        );
    }

    public TaskResultCallbackCommand decode(TransportResultIngressEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        TaskResultCallbackCommand payload = decodePayload(envelope.getPayload());
        CorrelationRecord correlation = decodeCorrelation(envelope.getCorrelation());
        String traceId = firstNonBlank(correlation.traceId(), envelope.diagnostic(TRACE_ID_FIELD));
        String attemptId = firstNonBlank(correlation.attemptId(), payload.attemptId());
        return new TaskResultCallbackCommand(
                payload.taskId(),
                payload.messageId(),
                payload.success(),
                payload.detail(),
                payload.errorCode(),
                payload.output(),
                attemptId,
                correlation.leaseToken(),
                traceId
        );
    }

    private String encodeWorkerResultRequestPayload(WorkerResultSubmitRequest request) {
        JsonObject payload = new JsonObject();
        payload.addProperty(RESULT_CORRELATION_REF_FIELD, request.resultCorrelationRef());
        payload.addProperty(TransportPacket.PAYLOAD_SUCCESS, request.success());
        addOptionalProperty(payload, TransportPacket.PAYLOAD_DETAIL, request.detail());
        addOptionalProperty(payload, TransportPacket.PAYLOAD_ERROR_CODE, request.errorCode());
        payload.add(TransportPacket.PAYLOAD_OUTPUT, gson.toJsonTree(request.output()));
        return gson.toJson(payload);
    }

    private static void addOptionalProperty(JsonObject object, String field, String value) {
        if (value != null && !value.isBlank()) {
            object.addProperty(field, value.trim());
        }
    }

    private TaskResultCallbackCommand decodePayload(String payloadJson) {
        JsonObject payload = parseObject(payloadJson, "payload");
        Boolean success = readBoolean(payload, TransportPacket.PAYLOAD_SUCCESS);
        if (success == null) {
            throw new IllegalArgumentException("result callback payload requires success");
        }
        String resultCorrelationRef = readString(payload, RESULT_CORRELATION_REF_FIELD);
        if (resultCorrelationRef == null) {
            throw new IllegalArgumentException("result callback payload requires resultCorrelationRef");
        }
        TaskDispatchDeliveryCorrelation deliveryCorrelation = deliveryCorrelationCodec.decode(resultCorrelationRef);
        String detail = firstNonBlank(
                readString(payload, TransportPacket.PAYLOAD_DETAIL),
                readString(payload, MESSAGE_FIELD)
        );
        String errorCode = readString(payload, TransportPacket.PAYLOAD_ERROR_CODE);
        Map<String, Object> output = readObject(payload, TransportPacket.PAYLOAD_OUTPUT);
        return new TaskResultCallbackCommand(
                deliveryCorrelation.taskId(),
                deliveryCorrelation.messageId(),
                success,
                detail,
                errorCode,
                output,
                deliveryCorrelation.attemptId(),
                null,
                null);
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

    private record CorrelationRecord(String attemptId,
                                     String leaseToken,
                                     String traceId) {
        private static final CorrelationRecord EMPTY = new CorrelationRecord(null, null, null);
    }
}
