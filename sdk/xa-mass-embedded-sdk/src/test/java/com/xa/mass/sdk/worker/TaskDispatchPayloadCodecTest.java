package com.xa.mass.sdk.worker;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.transport.channel.PulledDeliveryMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TaskDispatchPayloadCodecTest {

    private final TaskDispatchPayloadCodec payloadCodec = new TaskDispatchPayloadCodec();
    private final TaskDispatchDeliveryCorrelationCodec correlationCodec = new TaskDispatchDeliveryCorrelationCodec();

    @Test
    void encodesWorkerFramePayloadOutsideTransportCore() {
        TaskDispatchBinding binding = binding();

        String payload = payloadCodec.encode(context(), binding, binding.workerId());
        JsonObject frame = new Gson().fromJson(payload, JsonObject.class);

        assertEquals("msg-1", frame.get("messageId").getAsString());
        assertEquals("worker-1", frame.get("workerId").getAsString());
        assertEquals("task-1", frame.get("taskId").getAsString());
        assertEquals("crawler.fetch-page", frame.get("eventCode").getAsString());
        assertEquals("target-1", frame.getAsJsonObject("input").get("target").getAsString());
        assertEquals("fast", frame.getAsJsonObject("sharedConfig").get("mode").getAsString());
        assertEquals("batch-1", frame.get("batchId").getAsString());
        assertEquals(2, frame.get("retryCount").getAsInt());
        assertFalse(frame.has("routeKey"));
        assertFalse(frame.has("connectionId"));
        assertFalse(frame.has("deliveryQueueKey"));
    }

    @Test
    void decodesOpaquePulledDeliveryMessageIntoSdkTaskDispatch() {
        TaskDispatchBinding binding = binding();
        String payload = payloadCodec.encode(context(), binding, binding.workerId());
        String correlationRef = correlationCodec.encode(context(), binding);

        PulledTaskDispatch decoded = payloadCodec.decode(new PulledDeliveryMessage(
                "delivery-1",
                "worker-1",
                payload,
                correlationRef,
                10L
        ));

        assertEquals("task-1", decoded.getTaskId());
        assertEquals("msg-1", decoded.getMessageId());
        assertEquals("crawler.fetch-page", decoded.getEventCode());
        assertEquals("target-1", decoded.getInput().get("target"));
        assertEquals("fast", decoded.getSharedConfig().get("mode"));
        assertEquals("attempt-1", decoded.getAttemptId());
        assertEquals(3, decoded.getAttemptNo());
        assertEquals(2, decoded.getRetryCount());
        assertEquals("batch-1", decoded.getBatchId());
    }

    private static TaskDispatchContext context() {
        return new TaskDispatchContext("task-1", "task", "demo", "user", "crawler.fetch-page", Map.of("mode", "fast"));
    }

    private static TaskDispatchBinding binding() {
        return TaskDispatchBinding.workerLevelWithTransportEvidence(
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
                "polling",
                null,
                "test-fixture"
        );
    }
}
