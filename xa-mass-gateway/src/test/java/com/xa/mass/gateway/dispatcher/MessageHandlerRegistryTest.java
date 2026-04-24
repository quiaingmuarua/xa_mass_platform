package com.xa.mass.gateway.dispatcher;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.gateway.dispatcher.event.EventGatewayBridge;
import com.xa.mass.gateway.dispatcher.handler.MassMessageHandler;
import com.xa.mass.gateway.dispatcher.handler.ResolutionResult;
import com.xa.mass.gateway.dispatcher.handler.WorkerControlEventBridgeHandler;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.session.SessionRoles;
import com.xa.mass.sdk.event.EventResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class MessageHandlerRegistryTest {

    private static final Gson GSON = new Gson();

    private MessageHandlerRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new MessageHandlerRegistry();
    }

    @Test
    void resolveRegisteredTaskStepHandler() {
        MassMessageHandler handler = msg -> Collections.emptyList();
        registry.register(MessageType.TASK, "step", handler);

        ResolutionResult result = registry.resolve(MessageType.TASK, "step");

        assertTrue(result.isFound());
        assertSame(handler, result.getHandler());
    }

    @Test
    void resolveRegisteredWorkerControlEventResponseHandler() {
        MassMessageHandler handler = msg -> Collections.emptyList();
        registry.registerWorkerControlEventResponseHandler(handler);

        MassMessage response = massMessage("demoApp", MessageType.CONTROL, WorkerControlEventProtocol.SUB_MSG_TYPE);
        response.setResponse(true);

        ResolutionResult result = registry.resolve(response);

        assertTrue(result.isFound());
        assertSame(handler, result.getHandler());
        assertEquals("worker-control-event-response", result.getResolutionPath());
    }

    @Test
    void rejectsUnsupportedTupleRegistration() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> registry.register(MessageType.STATUS, "unknown", msg -> Collections.emptyList()));

        assertTrue(error.getMessage().contains("TASK/step"));
    }

    @Test
    void defaultNoHandlerResultIsNotFound() {
        ResolutionResult result = registry.resolve(MessageType.STATUS, "unknownSub");

        assertTrue(result.isNotFound());
        assertFalse(result.isFound());
    }

    @Test
    void rejectsDirectControlEventTupleRegistration() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> registry.register(MessageType.CONTROL,
                        WorkerControlEventProtocol.SUB_MSG_TYPE, msg -> Collections.emptyList()));

        assertTrue(error.getMessage().contains("CONTROL/event"));
    }

    @Test
    void builtinPingHandlerReturnsPong() {
        MassMessage ping = massMessage("GLOBAL", MessageType.PING, "heartbeat");

        ResolutionResult result = registry.resolve(ping);
        assertTrue(result.isFound());

        var responses = result.getHandler().handle(ping);
        assertEquals(1, responses.size());
        assertEquals(MessageType.PONG, responses.get(0).getMsgType());
    }

    @Test
    void builtinPongHandlerReturnsEmpty() {
        MassMessage pong = massMessage("GLOBAL", MessageType.PONG, "heartbeat");

        ResolutionResult result = registry.resolve(pong);
        assertTrue(result.isFound());
        assertTrue(result.getHandler().handle(pong).isEmpty());
    }

    @Test
    void resolveViaMassMessageDelegates() {
        registry.registerTaskStepHandler(msg -> Collections.emptyList());
        MassMessage msg = massMessage("GLOBAL", MessageType.TASK, "step");

        ResolutionResult result = registry.resolve(msg);
        assertTrue(result.isFound());
    }

    @Test
    void controlEventBridgeCapturesRequestFrames() {
        registry.registerWorkerControlEventBridge(new WorkerControlEventBridgeHandler(
                new EventGatewayBridge((request, principal) -> EventResponse.success(
                        java.util.Map.of("echoEvent", request.getEvent().value()),
                        request.getRequestId()))
        ));

        MassMessage msg = massMessage("demoApp", MessageType.CONTROL, WorkerControlEventProtocol.SUB_MSG_TYPE);
        JsonObject payload = new JsonObject();
        payload.addProperty(WorkerControlEventProtocol.EVENT_FIELD, "crawler.fetch-page");
        payload.addProperty(WorkerControlEventProtocol.REQUEST_ID_FIELD, "req-bridge");
        JsonObject requestPayload = new JsonObject();
        requestPayload.addProperty("url", "https://example.test");
        payload.add(WorkerControlEventProtocol.PAYLOAD_FIELD, requestPayload);
        msg.setPayload(payload);

        ResolutionResult result = registry.resolve(msg);
        assertTrue(result.isFound());
        assertEquals("worker-control-event-bridge", result.getResolutionPath());

        var responses = result.getHandler().handle(msg);
        assertEquals(1, responses.size());
        JsonObject responsePayload = GSON.fromJson(responses.get(0).getPayload(), JsonObject.class);
        assertTrue(responsePayload.get("success").getAsBoolean());
        assertEquals("req-bridge", responsePayload.get(WorkerControlEventProtocol.REQUEST_ID_FIELD).getAsString());
    }

    @Test
    void controlEventBridgeDoesNotCaptureResponseFrames() {
        registry.registerWorkerControlEventBridge(new WorkerControlEventBridgeHandler(
                new EventGatewayBridge((request, principal) -> EventResponse.success(
                        java.util.Map.of("echoEvent", request.getEvent().value()),
                        request.getRequestId()))
        ));

        MassMessage msg = massMessage("demoApp", MessageType.CONTROL, WorkerControlEventProtocol.SUB_MSG_TYPE);
        msg.setResponse(true);
        JsonObject payload = new JsonObject();
        payload.addProperty(WorkerControlEventProtocol.EVENT_FIELD, "crawler.fetch-page");
        msg.setPayload(payload);

        ResolutionResult result = registry.resolve(msg);
        assertTrue(result.isNotFound());
    }

    @Test
    void controlEventBridgeDoesNotCaptureNonEventSubtype() {
        registry.registerWorkerControlEventBridge(new WorkerControlEventBridgeHandler(
                new EventGatewayBridge((request, principal) -> EventResponse.success(
                        java.util.Map.of("echoEvent", request.getEvent().value()),
                        request.getRequestId()))
        ));

        MassMessage msg = massMessage("demoApp", MessageType.CONTROL, "legacy");
        JsonObject payload = new JsonObject();
        payload.addProperty(WorkerControlEventProtocol.EVENT_FIELD, "crawler.fetch-page");
        msg.setPayload(payload);

        ResolutionResult result = registry.resolve(msg);
        assertTrue(result.isNotFound());
    }

    @Test
    void controlEventBridgeDoesNotCaptureControlEventWithoutGlobalEventCode() {
        registry.registerWorkerControlEventBridge(new WorkerControlEventBridgeHandler(
                new EventGatewayBridge((request, principal) -> EventResponse.success(
                        java.util.Map.of("echoEvent", request.getEvent().value()),
                        request.getRequestId()))
        ));

        MassMessage msg = massMessage("demoApp", MessageType.CONTROL, WorkerControlEventProtocol.SUB_MSG_TYPE);
        msg.setPayload(new JsonObject());

        ResolutionResult result = registry.resolve(msg);
        assertTrue(result.isNotFound());
    }

    private MassMessage massMessage(String project, MessageType type, String subType) {
        MassMessage msg = new MassMessage();
        msg.setProject(project);
        msg.setMsgType(type);
        msg.setSubMsgType(subType);
        MessageContext ctx = new MessageContext();
        ctx.setWorkerId("worker-1");
        ctx.setConnRole(SessionRoles.TASK_MESSAGES);
        msg.setContext(ctx);
        return msg;
    }
}
