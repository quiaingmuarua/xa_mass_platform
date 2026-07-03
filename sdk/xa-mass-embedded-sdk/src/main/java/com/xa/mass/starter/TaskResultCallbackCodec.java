package com.xa.mass.starter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.xa.mass.sdk.worker.WorkerActionReply;
import com.xa.mass.transport.channel.ResultIngressDiagnostics;
import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.channel.ResultIngressMessage;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Starter-owned codec between worker result callback shape and opaque transport
 * ingress values.
 */
public final class TaskResultCallbackCodec {

    private static final String REPLY_REF_FIELD = "replyRef";
    private static final String CODE_FIELD = "code";
    private static final String BODY_FIELD = "body";
    private static final String MESSAGE_FIELD = "message";
    private static final String TRACE_ID_FIELD = "traceId";
    private static final String SUCCESS_FIELD = "success";

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

    public ResultIngressEntry toEntry(WorkerActionReply request,
                                      Map<String, String> diagnostics) {
        Objects.requireNonNull(request, "request");
        String replyRef = request.replyRef();
        return new ResultIngressEntry(
                replyRef,
                new ResultIngressMessage(
                        UUID.randomUUID().toString(),
                        replyRef,
                        encodeWorkerActionReplyPayload(request),
                        0L,
                        System.currentTimeMillis()
                ),
                new ResultIngressDiagnostics(diagnostics)
        );
    }

    public TaskResultCallbackCommand decode(ResultIngressEntry entry) {
        Objects.requireNonNull(entry, "entry");
        DecodedPayload payload = decodePayload(entry.message().payload());
        if (!payload.replyRef().equals(entry.message().resultCorrelationRef())) {
            throw new IllegalArgumentException("result ingress message correlation must match payload replyRef");
        }
        TaskResultCallbackCommand command = payload.command();
        String traceId = diagnostic(entry, TRACE_ID_FIELD);
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

    private String encodeWorkerActionReplyPayload(WorkerActionReply request) {
        JsonObject payload = new JsonObject();
        payload.addProperty(REPLY_REF_FIELD, request.replyRef());
        payload.addProperty(SUCCESS_FIELD, request.success());
        addOptionalProperty(payload, CODE_FIELD, request.code());
        addOptionalProperty(payload, BODY_FIELD, request.body());
        return gson.toJson(payload);
    }

    private static void addOptionalProperty(JsonObject object, String field, String value) {
        if (value != null && !value.isBlank()) {
            object.addProperty(field, value.trim());
        }
    }

    private DecodedPayload decodePayload(String payloadJson) {
        JsonObject payload = parseObject(payloadJson, "payload");
        Boolean success = readBoolean(payload, SUCCESS_FIELD);
        if (success == null) {
            throw new IllegalArgumentException("result callback payload requires success");
        }
        String replyRef = firstNonBlank(readString(payload, REPLY_REF_FIELD),
                readString(payload, "resultCorrelationRef"));
        if (replyRef == null) {
            throw new IllegalArgumentException("result callback payload requires replyRef");
        }
        TaskDispatchDeliveryCorrelation deliveryCorrelation = deliveryCorrelationCodec.decode(replyRef);
        String result = firstNonBlank(
                readString(payload, BODY_FIELD),
                readString(payload, "result"),
                readString(payload, MESSAGE_FIELD)
        );
        String resultCode = firstNonBlank(readString(payload, CODE_FIELD),
                readString(payload, "resultCode"));
        return new DecodedPayload(
                replyRef,
                new TaskResultCallbackCommand(
                        deliveryCorrelation.taskId(),
                        deliveryCorrelation.messageId(),
                        success,
                        success ? null : result,
                        resultCode,
                        success && result != null ? Map.of("result", result) : Map.of(),
                        deliveryCorrelation.attemptId(),
                        null,
                        null)
        );
    }

    private static String diagnostic(ResultIngressEntry entry, String key) {
        if (entry.diagnostics() == null || key == null || key.isBlank()) {
            return null;
        }
        return entry.diagnostics().get(key);
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

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record DecodedPayload(String replyRef,
                                  TaskResultCallbackCommand command) {
    }
}
