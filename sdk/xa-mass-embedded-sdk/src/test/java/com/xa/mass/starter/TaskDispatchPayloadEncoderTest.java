package com.xa.mass.starter;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TaskDispatchPayloadEncoderTest {

    private final TaskDispatchPayloadEncoder payloadEncoder = new TaskDispatchPayloadEncoder();
    private final TaskDispatchDeliveryCorrelationCodec correlationCodec = new TaskDispatchDeliveryCorrelationCodec();

    @Test
    void encodesWorkerFramePayloadOutsideTransportCore() {
        TaskDispatchBinding binding = binding();
        String correlationRef = correlationCodec.encode(context(), binding);
        String actionId = "delivery-1";

        String payload = payloadEncoder.encode(context(), binding, actionId, correlationRef);
        JsonObject frame = new Gson().fromJson(payload, JsonObject.class);

        assertEquals(actionId, frame.get("actionId").getAsString());
        assertEquals(correlationRef, frame.get("replyRef").getAsString());
        assertEquals("crawler.fetch-page", frame.get("eventCode").getAsString());
        JsonObject body = new Gson().fromJson(frame.get("body").getAsString(), JsonObject.class);
        assertEquals("target-1", body.get("target").getAsString());
        assertEquals("fast", frame.getAsJsonObject("sharedConfig").get("mode").getAsString());
        assertFalse(frame.has("taskId"));
        assertFalse(frame.has("messageId"));
        assertFalse(frame.has("workerId"));
        assertFalse(frame.has("batchId"));
        assertFalse(frame.has("retryCount"));
        assertFalse(frame.has("routeKey"));
        assertFalse(frame.has("connectionId"));
        assertFalse(frame.has("deliveryQueueKey"));
    }

    private static TaskDispatchContext context() {
        return new TaskDispatchContext("task-1", "task", "demo", "user", "crawler.fetch-page", Map.of("mode", "fast"));
    }

    private static TaskDispatchBinding binding() {
        return TaskDispatchBinding.workerLevelWithEvidence(
                "task-1",
                "msg-1",
                "crawler.fetch-page",
                Map.of("target", "target-1"),
                null,
                2,
                "attempt-1",
                3,
                "lease-1",
                "worker-1",
                "batch-1",
                "workers",
                null,
                null,
                null,
                "test-fixture"
        );
    }
}
