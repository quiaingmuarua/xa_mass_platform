package com.xa.mass.starter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * Starter-owned opaque correlation codec for transport delivery outcomes.
 */
final class TaskDispatchDeliveryCorrelationCodec {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String VERSION = "v1";

    private final Gson gson;

    TaskDispatchDeliveryCorrelationCodec() {
        this(new GsonBuilder().create());
    }

    TaskDispatchDeliveryCorrelationCodec(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    public String encode(TaskDispatchContext task, TaskDispatchBinding binding) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(binding, "binding");
        return encode(new TaskDispatchDeliveryCorrelation(
                task.taskId(),
                binding.messageId(),
                binding.attemptId(),
                binding.attemptNo()
        ));
    }

    String encode(TaskDispatchDeliveryCorrelation correlation) {
        Objects.requireNonNull(correlation, "correlation");
        String json = gson.toJson(new CorrelationRecord(
                VERSION,
                correlation.taskId(),
                correlation.messageId(),
                correlation.attemptId(),
                correlation.attemptNo()
        ));
        return ENCODER.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    TaskDispatchDeliveryCorrelation decode(String correlationRef) {
        if (correlationRef == null || correlationRef.isBlank()) {
            throw new IllegalArgumentException("correlationRef must not be blank");
        }
        CorrelationRecord record = gson.fromJson(
                new String(DECODER.decode(correlationRef), StandardCharsets.UTF_8),
                CorrelationRecord.class
        );
        if (record == null || !VERSION.equals(record.version)) {
            throw new IllegalArgumentException("unsupported delivery correlation");
        }
        return new TaskDispatchDeliveryCorrelation(
                record.taskId,
                record.messageId,
                record.attemptId,
                record.attemptNo
        );
    }

    private record CorrelationRecord(String version,
                                     String taskId,
                                     String messageId,
                                     String attemptId,
                                     int attemptNo) {
    }
}
