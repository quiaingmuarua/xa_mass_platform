package com.xa.mass.gateway.dispatcher;

import com.google.gson.JsonObject;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.base.debug.WorkerControlMessageProtocol;
import com.xa.mass.gateway.dispatcher.port.ControlEventRequestFrameBridge;
import com.xa.mass.gateway.dispatcher.port.ControlEventResponseFrameSink;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.gateway.queue.WebSocketGatewayFrameCodec;
import com.xa.mass.sdk.event.EventPrincipal;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventResponse;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.NoopWorkerSystemEventChannel;
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

    private WebSocketGatewayFrameCodec codec;
    private DispatcherContext context;
    private MessageTransporter<String, OutboundDelivery> transporter;
    private WorkerEndpointRegistry endpointRegistry;
    private GatewayInputProcessor inputProcessor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        codec = new WebSocketGatewayFrameCodec();
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
    void eventFirstControlRequestBridgeIsInvokedAndResponseEnqueued() {
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
        assertTrue(response.get(WorkerControlEventProtocol.RESPONSE_FIELD).getAsBoolean());
        assertEquals("mock.state.get", response.get(WorkerControlEventProtocol.EVENT_CODE_FIELD).getAsString());
        assertEquals("req-1", response.get(WorkerControlEventProtocol.REQUEST_ID_FIELD).getAsString());
    }

    @Test
    void noHandlerDefaultsToUnknownAndNoOutput() {
        boolean result = inputProcessor.process(codec.getGson().toJson(taskFrame("CONTROL", "unknown", false, "proj", new JsonObject())));

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

        inputProcessor.process(codec.getGson().toJson(taskFrame("TASK", "step", false, "proj", payload("status", "SUCCESS"))));

        org.mockito.ArgumentCaptor<OutboundDelivery> outputCaptor = org.mockito.ArgumentCaptor.forClass(OutboundDelivery.class);
        verify(transporter).sendOutput(outputCaptor.capture());
        JsonObject ack = codec.parseObject(outputCaptor.getValue().getRawJson());
        assertEquals(400, ack.getAsJsonObject("payload").get("code").getAsInt());
    }

    @Test
    void eventFirstControlResponseSinkConsumesDataWithoutOutput() {
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
        assertEquals("msg-2", messageId.get());
        assertEquals("mock.state.get", payload.get().get(WorkerControlEventProtocol.EVENT_CODE_FIELD).getAsString());
        verify(transporter, never()).sendOutput(any());
    }

    @Test
    void eventFirstControlRequestWithoutBridgeReturnsExplicitUnavailableResponse() {
        inputProcessor.process(controlEventRequest("proj", "mock.state.get"));

        org.mockito.ArgumentCaptor<OutboundDelivery> outputCaptor = org.mockito.ArgumentCaptor.forClass(OutboundDelivery.class);
        verify(transporter).sendOutput(outputCaptor.capture());
        JsonObject response = codec.parseObject(outputCaptor.getValue().getRawJson());
        assertEquals("CONTROL_EVENT_UNAVAILABLE", response.get(WorkerControlEventProtocol.CODE_FIELD).getAsString());
        assertEquals("control event bridge unavailable", response.get(WorkerControlEventProtocol.MESSAGE_FIELD).getAsString());
        assertEquals("req-1", response.get(WorkerControlEventProtocol.REQUEST_ID_FIELD).getAsString());
    }

    private DispatcherContext createContext(TaskResultIngestChannel taskResultIngestChannel,
                                            ControlEventRequestFrameBridge controlEventRequestFrameBridge,
                                            ControlEventResponseFrameSink controlEventResponseFrameSink) {
        return new DispatcherContext(
                transporter,
                endpointRegistry,
                codec,
                taskResultIngestChannel,
                NoopWorkerSystemEventChannel.INSTANCE,
                controlEventRequestFrameBridge,
                controlEventResponseFrameSink
        );
    }

    private String taskStepFrame(String taskId, String msgId, String status, String detail) {
        JsonObject payload = payload("status", status, "mockData", detail);
        JsonObject frame = taskFrame("TASK", "step", false, "proj", payload);
        frame.getAsJsonObject("context").addProperty("taskId", taskId);
        frame.addProperty("msgId", msgId);
        return codec.getGson().toJson(frame);
    }

    private String controlEventRequest(String project, String eventCode) {
        JsonObject frame = new JsonObject();
        frame.addProperty(WorkerControlEventProtocol.MESSAGE_ID_FIELD, "msg-1");
        frame.addProperty(WorkerControlEventProtocol.RESPONSE_FIELD, false);
        frame.addProperty(WorkerControlEventProtocol.WORKER_ID_FIELD, "worker-1");
        frame.addProperty(WorkerControlEventProtocol.PROJECT_FIELD, project);
        frame.addProperty(WorkerControlEventProtocol.EVENT_CODE_FIELD, eventCode);
        frame.addProperty(WorkerControlEventProtocol.REQUEST_ID_FIELD, "req-1");
        frame.add(WorkerControlEventProtocol.HEADERS_FIELD, payload("trace", "trace-1"));
        frame.add(WorkerControlEventProtocol.PAYLOAD_FIELD, payload("verbose", true));
        frame.add(WorkerControlEventProtocol.PRINCIPAL_FIELD, payload(
                WorkerControlEventProtocol.CLIENT_ID_FIELD, "client-1",
                WorkerControlEventProtocol.USER_ID_FIELD, "user-1"
        ));
        return codec.getGson().toJson(frame);
    }

    private String controlEventResponse(String project, String eventCode) {
        JsonObject frame = new JsonObject();
        frame.addProperty(WorkerControlEventProtocol.MESSAGE_ID_FIELD, "msg-2");
        frame.addProperty(WorkerControlEventProtocol.RESPONSE_FIELD, true);
        frame.addProperty(WorkerControlEventProtocol.WORKER_ID_FIELD, "worker-1");
        frame.addProperty(WorkerControlEventProtocol.PROJECT_FIELD, project);
        frame.addProperty(WorkerControlEventProtocol.EVENT_CODE_FIELD, eventCode);
        frame.addProperty(WorkerControlEventProtocol.REQUEST_ID_FIELD, "req-1");
        frame.addProperty(WorkerControlEventProtocol.SUCCESS_FIELD, true);
        frame.addProperty(WorkerControlEventProtocol.CODE_FIELD, "OK");
        frame.addProperty(WorkerControlEventProtocol.MESSAGE_FIELD, "success");
        frame.add(WorkerControlEventProtocol.DATA_FIELD, payload(
                WorkerControlMessageProtocol.REPLY_TO_MESSAGE_ID_FIELD, "msg-1",
                WorkerControlEventProtocol.EVENT_CODE_FIELD, eventCode
        ));
        return codec.getGson().toJson(frame);
    }

    private JsonObject taskFrame(String msgType, String subMsgType, boolean response, String project, JsonObject payload) {
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
