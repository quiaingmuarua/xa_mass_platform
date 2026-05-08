package com.xa.mass.transport.websocket.dispatcher;

import com.google.gson.JsonObject;
import com.xa.mass.transport.websocket.queue.WebSocketTransportFrameCodec;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.NoopWorkerSystemEventChannel;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.model.TaskResultReport;
import com.xa.mass.transport.model.TransportResultEnvelope;
import com.xa.mass.transport.packet.TransportPacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WebSocketInputProcessorTest {

    private WebSocketTransportFrameCodec codec;
    private WebSocketDispatcherContext context;
    private WorkerEndpointRegistry endpointRegistry;
    private WebSocketInputProcessor inputProcessor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        codec = new WebSocketTransportFrameCodec();
        endpointRegistry = mock(WorkerEndpointRegistry.class);
        context = createContext(null);
        inputProcessor = new WebSocketInputProcessor(context);
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
        unsupportedFrame.addProperty(TransportPacket.PAYLOAD_WORKER_ID, "worker-1");
        unsupportedFrame.addProperty(TransportPacket.PAYLOAD_PROJECT, "proj");
        unsupportedFrame.addProperty("eventCode", "mock.state.get");

        boolean result = inputProcessor.process(codec.getGson().toJson(unsupportedFrame));

        assertTrue(result);
    }

    @Test
    void canonicalTaskResultIngestsWithoutOutput() {
        AtomicReference<TransportResultEnvelope> capturedEnvelope = new AtomicReference<>();
        context = createContext(new TaskResultIngestChannel() {
            @Override
            public boolean ingest(TaskResultReport report) {
                return true;
            }

            @Override
            public boolean ingest(TransportResultEnvelope envelope) {
                capturedEnvelope.set(envelope);
                return true;
            }
        });
        inputProcessor = new WebSocketInputProcessor(context);

        boolean result = inputProcessor.process(canonicalTaskResultFrame("task-1", "msg-1", true, "ok"));

        assertTrue(result);
        assertNotNull(capturedEnvelope.get());
        assertEquals("websocket", capturedEnvelope.get().getAdapterId());
        assertEquals("worker-1", capturedEnvelope.get().getRouteKey());
        assertEquals("task-1", capturedEnvelope.get().getTaskId());
        assertEquals("msg-1", capturedEnvelope.get().getMessageId());
        assertTrue(capturedEnvelope.get().getReport().isSuccess());
        assertEquals("ok", capturedEnvelope.get().getReport().getDetail());
        assertEquals("SUCCESS", capturedEnvelope.get().getReport().getOutput().get("status"));
    }

    @Test
    void canonicalTaskResultUsesInboundMetadataWhenFrameOmitsWorkerId() {
        AtomicReference<TransportResultEnvelope> capturedEnvelope = new AtomicReference<>();
        context = createContext(new TaskResultIngestChannel() {
            @Override
            public boolean ingest(TaskResultReport report) {
                return true;
            }

            @Override
            public boolean ingest(TransportResultEnvelope envelope) {
                capturedEnvelope.set(envelope);
                return true;
            }
        });
        inputProcessor = new WebSocketInputProcessor(context);

        JsonObject frame = new JsonObject();
        frame.addProperty("messageId", "msg-1");
        frame.addProperty("taskId", "task-1");
        frame.addProperty(TransportPacket.PAYLOAD_SUCCESS, true);
        frame.addProperty(TransportPacket.PAYLOAD_DETAIL, "ok");

        boolean result = inputProcessor.process(WebSocketInboundMessage.of(
                codec.getGson().toJson(frame),
                "worker-from-handshake",
                "route-from-handshake",
                "endpoint-1"
        ));

        assertTrue(result);
        assertNotNull(capturedEnvelope.get());
        assertEquals("route-from-handshake", capturedEnvelope.get().getRouteKey());
        assertEquals("task-1", capturedEnvelope.get().getTaskId());
        assertEquals("msg-1", capturedEnvelope.get().getMessageId());
    }

    @Test
    void canonicalTaskResultCanReuseParsedInboundFrame() {
        AtomicReference<TransportResultEnvelope> capturedEnvelope = new AtomicReference<>();
        context = createContext(new TaskResultIngestChannel() {
            @Override
            public boolean ingest(TaskResultReport report) {
                return true;
            }

            @Override
            public boolean ingest(TransportResultEnvelope envelope) {
                capturedEnvelope.set(envelope);
                return true;
            }
        });
        inputProcessor = new WebSocketInputProcessor(context);

        JsonObject frame = new JsonObject();
        frame.addProperty("messageId", "msg-1");
        frame.addProperty("taskId", "task-1");
        frame.addProperty(TransportPacket.PAYLOAD_SUCCESS, true);
        frame.addProperty(TransportPacket.PAYLOAD_DETAIL, "ok");
        frame.add(TransportPacket.PAYLOAD_OUTPUT, payload("status", "SUCCESS"));

        boolean result = inputProcessor.process(WebSocketInboundMessage.of(
                "not-json-but-already-parsed",
                "worker-from-session",
                "route-from-session",
                "endpoint-1",
                frame
        ));

        assertTrue(result);
        assertNotNull(capturedEnvelope.get());
        assertEquals("route-from-session", capturedEnvelope.get().getRouteKey());
        assertEquals("task-1", capturedEnvelope.get().getTaskId());
        assertEquals("msg-1", capturedEnvelope.get().getMessageId());
    }

    @Test
    void canonicalTaskResultPrefersInlineRouteKeyOverSessionMetadata() {
        AtomicReference<TransportResultEnvelope> capturedEnvelope = new AtomicReference<>();
        context = createContext(new TaskResultIngestChannel() {
            @Override
            public boolean ingest(TaskResultReport report) {
                return true;
            }

            @Override
            public boolean ingest(TransportResultEnvelope envelope) {
                capturedEnvelope.set(envelope);
                return true;
            }
        });
        inputProcessor = new WebSocketInputProcessor(context);

        JsonObject frame = new JsonObject();
        frame.addProperty("messageId", "msg-1");
        frame.addProperty("taskId", "task-1");
        frame.addProperty(TransportPacket.PAYLOAD_SUCCESS, true);
        frame.addProperty("routeKey", "inline-route");
        frame.addProperty(TransportPacket.PAYLOAD_DETAIL, "ok");

        boolean result = inputProcessor.process(WebSocketInboundMessage.of(
                codec.getGson().toJson(frame),
                "worker-from-session",
                "route-from-session",
                "endpoint-1"
        ));

        assertTrue(result);
        assertNotNull(capturedEnvelope.get());
        assertEquals("inline-route", capturedEnvelope.get().getRouteKey());
    }

    @Test
    void canonicalTaskResultReturnsFalseWhenIngestChannelRejectsEnvelope() {
        context = createContext(new TaskResultIngestChannel() {
            @Override
            public boolean ingest(TaskResultReport report) {
                return false;
            }

            @Override
            public boolean ingest(TransportResultEnvelope envelope) {
                return false;
            }
        });
        inputProcessor = new WebSocketInputProcessor(context);

        boolean result = inputProcessor.process(canonicalTaskResultFrame("task-1", "msg-1", true, "ok"));

        assertFalse(result);
    }

    @Test
    void canonicalTaskResultWithoutMessageIdIsRejectedWithoutIngest() {
        AtomicReference<TransportResultEnvelope> capturedEnvelope = new AtomicReference<>();
        context = createContext(new TaskResultIngestChannel() {
            @Override
            public boolean ingest(TaskResultReport report) {
                return true;
            }

            @Override
            public boolean ingest(TransportResultEnvelope envelope) {
                capturedEnvelope.set(envelope);
                return true;
            }
        });
        inputProcessor = new WebSocketInputProcessor(context);

        JsonObject frame = new JsonObject();
        frame.addProperty("taskId", "task-1");
        frame.addProperty(TransportPacket.PAYLOAD_SUCCESS, true);
        frame.addProperty(TransportPacket.PAYLOAD_DETAIL, "ok");

        boolean result = inputProcessor.process(WebSocketInboundMessage.of(
                codec.getGson().toJson(frame),
                "worker-1",
                "route-1",
                "endpoint-1"
        ));

        assertTrue(result);
        assertNull(capturedEnvelope.get());
    }

    private WebSocketDispatcherContext createContext(TaskResultIngestChannel taskResultIngestChannel) {
        return new WebSocketDispatcherContext(
                "websocket",
                endpointRegistry,
                codec,
                taskResultIngestChannel,
                NoopWorkerSystemEventChannel.INSTANCE
        );
    }

    private String canonicalTaskResultFrame(String taskId, String messageId, boolean success, String detail) {
        JsonObject frame = new JsonObject();
        frame.addProperty("messageId", messageId);
        frame.addProperty(TransportPacket.PAYLOAD_WORKER_ID, "worker-1");
        frame.addProperty(TransportPacket.PAYLOAD_PROJECT, "proj");
        frame.addProperty("taskId", taskId);
        frame.addProperty(TransportPacket.PAYLOAD_SUCCESS, success);
        frame.addProperty(TransportPacket.PAYLOAD_DETAIL, detail);
        frame.add(TransportPacket.PAYLOAD_OUTPUT,
                payload("status", success ? "SUCCESS" : "FAILED", "mockData", detail));
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
