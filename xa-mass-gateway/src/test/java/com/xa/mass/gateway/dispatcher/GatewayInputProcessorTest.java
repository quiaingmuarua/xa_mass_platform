package com.xa.mass.gateway.dispatcher;

import com.google.gson.JsonObject;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.gateway.dispatcher.port.ControlEventRequestFrameBridge;
import com.xa.mass.gateway.dispatcher.port.ControlEventResponseFrameSink;
import com.xa.mass.gateway.queue.GsonMessageCodec;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.sdk.event.EventPrincipal;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventResponse;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class GatewayInputProcessorTest {

    private GatewayFrameRouter frameRouter;
    private GsonMessageCodec codec;
    private DispatcherContext context;
    private MessageTransporter<String, OutboundDelivery> transporter;
    private WorkerEndpointRegistry endpointRegistry;
    private GatewayInputProcessor inputProcessor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        codec = new GsonMessageCodec();
        frameRouter = new GatewayFrameRouter(codec);
        transporter = mock(MessageTransporter.class);
        endpointRegistry = mock(WorkerEndpointRegistry.class);
        context = createContext(null, null, null);
        inputProcessor = new GatewayInputProcessor(context);
    }

    @Test
    void nullDecodeSkipsHandlingAndReturnsTrue() {
        boolean result = inputProcessor.process("not-valid-json-at-all-{{{}}}");
        assertTrue(result);
    }

    @Test
    void controlEventRequestBridgeIsInvokedAndResponseEnqueued() {
        AtomicReference<EventRequest> capturedRequest = new AtomicReference<>();
        AtomicReference<EventPrincipal> capturedPrincipal = new AtomicReference<>();
        context = createContext(null, (request, principal) -> {
            capturedRequest.set(request);
            capturedPrincipal.set(principal);
            return EventResponse.success(Map.of("ack", true), request.getRequestId());
        }, null);
        inputProcessor = new GatewayInputProcessor(context);

        inputProcessor.process(controlEventRequest("proj", "mock.state.get"));

        assertNotNull(capturedRequest.get());
        assertEquals("mock.state.get", capturedRequest.get().getEvent().value());
        assertEquals("req-1", capturedRequest.get().getRequestId());
        assertEquals("client-1", capturedPrincipal.get().getClientId());
        org.mockito.ArgumentCaptor<OutboundDelivery> outputCaptor = org.mockito.ArgumentCaptor.forClass(OutboundDelivery.class);
        verify(transporter).sendOutput(outputCaptor.capture());
        JsonObject response = codec.parseObject(outputCaptor.getValue().getRawJson());
        assertEquals("worker-1", outputCaptor.getValue().getWorkerId());
        assertEquals("msg-1", outputCaptor.getValue().getTraceId());
        assertTrue(response.get("response").getAsBoolean());
        assertEquals("CONTROL", response.get("msgType").getAsString());
    }

    @Test
    void noHandlerDefaultsToUnknownAndNoOutput() {
        boolean result = inputProcessor.process(
                codec.getGson().toJson(frame("CONTROL", "unknown", false, "proj", new JsonObject()))
        );

        assertTrue(result);
        verify(transporter, never()).sendOutput(any());
    }

    @Test
    void taskStepBridgeProducesAckWithoutEventBackfill() {
        context = createContext(report -> true, null, null);
        inputProcessor = new GatewayInputProcessor(context);

        inputProcessor.process(taskStepFrame("task-1", "msg-1", "SUCCESS", "ok"));

        org.mockito.ArgumentCaptor<OutboundDelivery> outputCaptor = org.mockito.ArgumentCaptor.forClass(OutboundDelivery.class);
        verify(transporter).sendOutput(outputCaptor.capture());
        JsonObject ack = codec.parseObject(outputCaptor.getValue().getRawJson());
        assertEquals("worker-1", outputCaptor.getValue().getWorkerId());
        assertEquals("msg-1", outputCaptor.getValue().getTraceId());
        assertEquals(200, ack.getAsJsonObject("payload").get("code").getAsInt());
        assertNull(codec.extractEventCode(ack));
    }

    @Test
    void taskStepWithoutBridgeReturnsExplicitUnavailableAck() {
        inputProcessor.process(taskStepFrame("task-1", "msg-1", "SUCCESS", "ok"));

        org.mockito.ArgumentCaptor<OutboundDelivery> outputCaptor = org.mockito.ArgumentCaptor.forClass(OutboundDelivery.class);
        verify(transporter).sendOutput(outputCaptor.capture());
        JsonObject ack = codec.parseObject(outputCaptor.getValue().getRawJson());
        assertEquals(503, ack.getAsJsonObject("payload").get("code").getAsInt());
        assertEquals("task step bridge unavailable", ack.getAsJsonObject("payload").get("message").getAsString());
    }

    @Test
    void taskStepBridgeRejectsMalformedTaskReportWithBadRequestAck() {
        context = createContext(report -> true, null, null);
        inputProcessor = new GatewayInputProcessor(context);

        inputProcessor.process(
                codec.getGson().toJson(frame("TASK", "step", false, "proj", payload("status", "SUCCESS")))
        );

        org.mockito.ArgumentCaptor<OutboundDelivery> outputCaptor = org.mockito.ArgumentCaptor.forClass(OutboundDelivery.class);
        verify(transporter).sendOutput(outputCaptor.capture());
        JsonObject ack = codec.parseObject(outputCaptor.getValue().getRawJson());
        assertEquals(400, ack.getAsJsonObject("payload").get("code").getAsInt());
    }

    @Test
    void controlEventResponseSinkConsumesWithoutOutput() {
        AtomicReference<String> raw = new AtomicReference<>();
        AtomicReference<String> workerId = new AtomicReference<>();
        AtomicReference<String> project = new AtomicReference<>();
        AtomicReference<String> messageId = new AtomicReference<>();
        AtomicReference<JsonObject> payload = new AtomicReference<>();
        context = createContext(null, null, (responseRaw, responseWorkerId, responseProject, responseMessageId, responsePayload) -> {
            raw.set(responseRaw);
            workerId.set(responseWorkerId);
            project.set(responseProject);
            messageId.set(responseMessageId);
            payload.set(responsePayload);
        });
        inputProcessor = new GatewayInputProcessor(context);

        boolean result = inputProcessor.process(controlEventResponse("proj", "mock.state.get"));

        assertTrue(result);
        assertNotNull(raw.get());
        assertEquals("worker-1", workerId.get());
        assertEquals("proj", project.get());
        assertEquals("msg-1", messageId.get());
        assertEquals("mock.state.get", payload.get().get(WorkerControlEventProtocol.EVENT_FIELD).getAsString());
        verify(transporter, never()).sendOutput(any());
    }

    @Test
    void controlEventRequestWithoutBridgeReturnsExplicitUnavailableResponse() {
        inputProcessor.process(controlEventRequest("proj", "mock.state.get"));

        org.mockito.ArgumentCaptor<OutboundDelivery> outputCaptor = org.mockito.ArgumentCaptor.forClass(OutboundDelivery.class);
        verify(transporter).sendOutput(outputCaptor.capture());
        JsonObject response = codec.parseObject(outputCaptor.getValue().getRawJson());
        assertEquals("CONTROL", response.get("msgType").getAsString());
        assertEquals("CONTROL_EVENT_UNAVAILABLE", response.getAsJsonObject("payload").get("code").getAsString());
        assertEquals("control event bridge unavailable", response.getAsJsonObject("payload").get("message").getAsString());
        assertEquals("req-1", response.getAsJsonObject("payload").get("requestId").getAsString());
    }

    private DispatcherContext createContext(TaskResultIngestChannel taskResultIngestChannel,
                                            ControlEventRequestFrameBridge controlEventRequestFrameBridge,
                                            ControlEventResponseFrameSink controlEventResponseFrameSink) {
        return new DispatcherContext(
                transporter,
                endpointRegistry,
                codec,
                frameRouter,
                taskResultIngestChannel,
                controlEventRequestFrameBridge,
                controlEventResponseFrameSink
        );
    }

    private String taskStepFrame(String taskId, String msgId, String status, String detail) {
        JsonObject payload = payload("status", status, "mockData", detail);
        JsonObject frame = frame("TASK", "step", false, "proj", payload);
        frame.getAsJsonObject("context").addProperty("taskId", taskId);
        frame.addProperty("msgId", msgId);
        return codec.getGson().toJson(frame);
    }

    private String controlEventRequest(String project, String eventCode) {
        return codec.getGson().toJson(frame("CONTROL", WorkerControlEventProtocol.SUB_MSG_TYPE, false, project, payload(
                WorkerControlEventProtocol.EVENT_FIELD, eventCode,
                WorkerControlEventProtocol.REQUEST_ID_FIELD, "req-1",
                WorkerControlEventProtocol.PAYLOAD_FIELD, payload("verbose", true),
                WorkerControlEventProtocol.PRINCIPAL_FIELD, payload(
                        WorkerControlEventProtocol.CLIENT_ID_FIELD, "client-1",
                        WorkerControlEventProtocol.USER_ID_FIELD, "user-1"
                )
        )));
    }

    private String controlEventResponse(String project, String eventCode) {
        return codec.getGson().toJson(frame("CONTROL", WorkerControlEventProtocol.SUB_MSG_TYPE, true, project, payload(
                WorkerControlEventProtocol.EVENT_FIELD, eventCode,
                WorkerControlEventProtocol.REQUEST_ID_FIELD, "req-1"
        )));
    }

    private JsonObject frame(String msgType, String subMsgType, boolean response, String project, JsonObject payload) {
        JsonObject frame = new JsonObject();
        frame.addProperty("msgId", "msg-1");
        frame.addProperty("msgType", msgType);
        frame.addProperty("subMsgType", subMsgType);
        frame.addProperty("response", response);
        frame.addProperty("from", response ? "CLIENT" : "SERVER");
        if (project != null) {
            frame.addProperty("project", project);
        }
        JsonObject context = new JsonObject();
        context.addProperty("workerId", "worker-1");
        context.addProperty("connRole", "task_messages");
        frame.add("context", context);
        frame.add("payload", payload != null ? payload : new JsonObject());
        return frame;
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
