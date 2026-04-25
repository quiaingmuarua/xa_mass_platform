package com.xa.mass.gateway.dispatcher;

import com.google.gson.JsonObject;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.gateway.queue.WebSocketTransportFrameCodec;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.NoopWorkerSystemEventChannel;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class GatewayInputProcessorTest {

    private WebSocketTransportFrameCodec codec;
    private DispatcherContext context;
    private MessageTransporter<String, OutboundDelivery> transporter;
    private WorkerEndpointRegistry endpointRegistry;
    private GatewayInputProcessor inputProcessor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        codec = new WebSocketTransportFrameCodec();
        transporter = mock(MessageTransporter.class);
        endpointRegistry = mock(WorkerEndpointRegistry.class);
        context = createContext(null);
        inputProcessor = new GatewayInputProcessor(context);
    }

    @Test
    void nullDecodeSkipsHandlingAndReturnsTrue() {
        boolean result = inputProcessor.process("not-valid-json-at-all-{{{}}}");
        assertTrue(result);
    }

    @Test
    void unsupportedFrameShapeIsIgnoredWithoutOutput() {
        JsonObject unsupportedFrame = new JsonObject();
        unsupportedFrame.addProperty("messageId", "msg-1");
        unsupportedFrame.addProperty("workerId", "worker-1");
        unsupportedFrame.addProperty("project", "proj");
        unsupportedFrame.addProperty("eventCode", "mock.state.get");

        boolean result = inputProcessor.process(codec.getGson().toJson(unsupportedFrame));

        assertTrue(result);
        verify(transporter, never()).sendOutput(any());
    }

    @Test
    void canonicalTaskResultIngestsWithoutOutput() {
        AtomicReference<com.xa.mass.transport.model.TaskResultReport> capturedReport = new AtomicReference<>();
        context = createContext(report -> {
            capturedReport.set(report);
            return true;
        });
        inputProcessor = new GatewayInputProcessor(context);

        boolean result = inputProcessor.process(canonicalTaskResultFrame("task-1", "msg-1", true, "ok"));

        assertTrue(result);
        assertNotNull(capturedReport.get());
        assertEquals("task-1", capturedReport.get().getTaskId());
        assertEquals("msg-1", capturedReport.get().getMessageId());
        assertTrue(capturedReport.get().isSuccess());
        assertEquals("ok", capturedReport.get().getDetail());
        assertEquals("SUCCESS", capturedReport.get().getOutput().get("status"));
        verify(transporter, never()).sendOutput(any());
    }

    private DispatcherContext createContext(TaskResultIngestChannel taskResultIngestChannel) {
        return new DispatcherContext(
                transporter,
                endpointRegistry,
                codec,
                taskResultIngestChannel,
                NoopWorkerSystemEventChannel.INSTANCE
        );
    }

    private String canonicalTaskResultFrame(String taskId, String messageId, boolean success, String detail) {
        JsonObject frame = new JsonObject();
        frame.addProperty("messageId", messageId);
        frame.addProperty("workerId", "worker-1");
        frame.addProperty("project", "proj");
        frame.addProperty("taskId", taskId);
        frame.addProperty("success", success);
        frame.addProperty("detail", detail);
        frame.add("output", payload("status", success ? "SUCCESS" : "FAILED", "mockData", detail));
        return codec.getGson().toJson(frame);
    }

    private JsonObject payload(Object... keyValues) {
        JsonObject payload = new JsonObject();
        for (int i = 0; i < keyValues.length; i += 2) {
            String key = (String) keyValues[i];
            Object value = keyValues[i + 1];
            if (value instanceof String str) {
                payload.addProperty(key, str);
            } else if (value instanceof Boolean bool) {
                payload.addProperty(key, bool);
            } else if (value instanceof Number number) {
                payload.addProperty(key, number);
            } else if (value instanceof JsonObject object) {
                payload.add(key, object);
            }
        }
        return payload;
    }
}
