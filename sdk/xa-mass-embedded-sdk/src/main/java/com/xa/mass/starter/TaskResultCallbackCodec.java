package com.xa.mass.starter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.xa.mass.sdk.worker.WorkerResultSubmission;
import com.xa.mass.transport.packet.TransportPacket;
import com.xa.mass.transport.routing.RoutingEnvelope;
import com.xa.mass.transport.routing.RoutingOwnerKinds;
import com.xa.mass.transport.routing.RoutingTarget;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Starter-owned codec between worker result callback shape and opaque transport
 * ingress values.
 */
public final class TaskResultCallbackCodec {

    private static final String RESULT_CORRELATION_REF_FIELD = "resultCorrelationRef";
    private static final String RESULT_CODE_FIELD = "resultCode";
    private static final String RESULT_FIELD = "result";
    private static final String MESSAGE_FIELD = "message";
    private static final String TRACE_ID_FIELD = "traceId";

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

    public RoutingEnvelope toEnvelope(WorkerResultSubmission request,
                                      Map<String, String> diagnostics) {
        Objects.requireNonNull(request, "request");
        return new RoutingEnvelope(
                UUID.randomUUID().toString(),
                RoutingTarget.resultIngress(request.resultCorrelationRef()),
                encodeWorkerResultRequestPayload(request),
                diagnostics,
                System.currentTimeMillis()
        );
    }

    public TaskResultCallbackCommand decode(RoutingEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        validateResultIngressTarget(envelope);
        DecodedPayload payload = decodePayload(envelope.payload());
        if (!payload.resultCorrelationRef().equals(envelope.target().ownerRef())) {
            throw new IllegalArgumentException("result envelope target ownerRef must match payload resultCorrelationRef");
        }
        TaskResultCallbackCommand command = payload.command();
        String traceId = diagnostic(envelope, TRACE_ID_FIELD);
        return new TaskResultCallbackCommand(
                command.taskId(),
                command.messageId(),
                command.success(),
                command.detail(),
                command.errorCode(),
                command.output(),
                command.attemptId(),
                command.leaseToken(),
                traceId
        );
    }

    private String encodeWorkerResultRequestPayload(WorkerResultSubmission request) {
        JsonObject payload = new JsonObject();
        payload.addProperty(RESULT_CORRELATION_REF_FIELD, request.resultCorrelationRef());
        payload.addProperty(TransportPacket.PAYLOAD_SUCCESS, request.success());
        addOptionalProperty(payload, RESULT_CODE_FIELD, request.resultCode());
        addOptionalProperty(payload, RESULT_FIELD, request.result());
        return gson.toJson(payload);
    }

    private static void addOptionalProperty(JsonObject object, String field, String value) {
        if (value != null && !value.isBlank()) {
            object.addProperty(field, value.trim());
        }
    }

    private DecodedPayload decodePayload(String payloadJson) {
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
        String result = firstNonBlank(
                readString(payload, RESULT_FIELD),
                readString(payload, MESSAGE_FIELD)
        );
        String resultCode = readString(payload, RESULT_CODE_FIELD);
        return new DecodedPayload(
                resultCorrelationRef,
                new TaskResultCallbackCommand(
                        deliveryCorrelation.taskId(),
                        deliveryCorrelation.messageId(),
                        success,
                        success ? null : result,
                        resultCode,
                        success && result != null ? Map.of(RESULT_FIELD, result) : Map.of(),
                        deliveryCorrelation.attemptId(),
                        null,
                        null)
        );
    }

    private static void validateResultIngressTarget(RoutingEnvelope envelope) {
        if (envelope.target() == null || !RoutingOwnerKinds.RESULT_INGRESS.equals(envelope.target().ownerKind())) {
            throw new IllegalArgumentException("result envelope target ownerKind must be result-ingress");
        }
    }

    private static String diagnostic(RoutingEnvelope envelope, String key) {
        if (envelope.diagnostics() == null || key == null || key.isBlank()) {
            return null;
        }
        return envelope.diagnostics().get(key);
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

    private record DecodedPayload(String resultCorrelationRef,
                                  TaskResultCallbackCommand command) {
    }
}
