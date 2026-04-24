package com.xa.mass.gateway.dispatcher;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.gateway.dispatcher.bridge.WorkerControlEventRequestBridge;
import com.xa.mass.gateway.dispatcher.event.EventGatewayBridge;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.session.SessionRoles;
import com.xa.mass.sdk.event.EventResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GatewayFrameRouterTest {

    private static final Gson GSON = new Gson();

    private GatewayFrameRouter frameRouter;

    @BeforeEach
    void setUp() {
        frameRouter = new GatewayFrameRouter();
    }

    @Test
    void classifyTaskStepFrame() {
        GatewayFrameKind result = frameRouter.route(massMessage("demoApp", MessageType.TASK, "step"));
        assertEquals(GatewayFrameKind.TASK_STEP, result);
    }

    @Test
    void classifyWorkerControlEventResponseFrame() {
        MassMessage response = massMessage("demoApp", MessageType.CONTROL, WorkerControlEventProtocol.SUB_MSG_TYPE);
        response.setResponse(true);

        GatewayFrameKind result = frameRouter.route(response);
        assertEquals(GatewayFrameKind.CONTROL_EVENT_RESPONSE, result);
    }

    @Test
    void defaultNoHandlerResultIsNotFound() {
        GatewayFrameKind result = frameRouter.route(massMessage("demoApp", MessageType.CONTROL, "unknownSub"));
        assertEquals(GatewayFrameKind.UNKNOWN, result);
    }

    @Test
    void builtinPingHandlerReturnsPong() {
        MassMessage ping = massMessage("GLOBAL", MessageType.PING, "heartbeat");

        GatewayFrameKind result = frameRouter.route(ping);
        assertEquals(GatewayFrameKind.PING_HEARTBEAT, result);

        var responses = frameRouter.handlePing(ping);
        assertEquals(1, responses.size());
        assertEquals(MessageType.PONG, responses.get(0).getMsgType());
    }

    @Test
    void builtinPongHandlerReturnsEmpty() {
        MassMessage pong = massMessage("GLOBAL", MessageType.PONG, "heartbeat");

        GatewayFrameKind result = frameRouter.route(pong);
        assertEquals(GatewayFrameKind.PONG_HEARTBEAT, result);
        assertTrue(frameRouter.handlePong(pong).isEmpty());
    }

    @Test
    void routeUsesMassMessageTupleClassification() {
        MassMessage msg = massMessage("GLOBAL", MessageType.TASK, "step");

        GatewayFrameKind result = frameRouter.route(msg);
        assertEquals(GatewayFrameKind.TASK_STEP, result);
    }

    @Test
    void controlEventBridgeCapturesRequestFrames() {
        WorkerControlEventRequestBridge bridge = new WorkerControlEventRequestBridge(
                new EventGatewayBridge((request, principal) -> EventResponse.success(
                        java.util.Map.of("echoEvent", request.getEvent().value()),
                        request.getRequestId()))
        );

        MassMessage msg = massMessage("demoApp", MessageType.CONTROL, WorkerControlEventProtocol.SUB_MSG_TYPE);
        JsonObject payload = new JsonObject();
        payload.addProperty(WorkerControlEventProtocol.EVENT_FIELD, "crawler.fetch-page");
        payload.addProperty(WorkerControlEventProtocol.REQUEST_ID_FIELD, "req-bridge");
        JsonObject requestPayload = new JsonObject();
        requestPayload.addProperty("url", "https://example.test");
        payload.add(WorkerControlEventProtocol.PAYLOAD_FIELD, requestPayload);
        msg.setPayload(payload);

        GatewayFrameKind result = frameRouter.route(msg);
        assertEquals(GatewayFrameKind.CONTROL_EVENT_REQUEST, result);

        var responses = bridge.handleControlEventRequest(msg);
        assertEquals(1, responses.size());
        JsonObject responsePayload = GSON.fromJson(responses.get(0).getPayload(), JsonObject.class);
        assertTrue(responsePayload.get("success").getAsBoolean());
        assertEquals("req-bridge", responsePayload.get(WorkerControlEventProtocol.REQUEST_ID_FIELD).getAsString());
    }

    @Test
    void controlEventBridgeDoesNotCaptureResponseFrames() {
        MassMessage msg = massMessage("demoApp", MessageType.CONTROL, WorkerControlEventProtocol.SUB_MSG_TYPE);
        msg.setResponse(true);
        JsonObject payload = new JsonObject();
        payload.addProperty(WorkerControlEventProtocol.EVENT_FIELD, "crawler.fetch-page");
        msg.setPayload(payload);

        GatewayFrameKind result = frameRouter.route(msg);
        assertEquals(GatewayFrameKind.CONTROL_EVENT_RESPONSE, result);
    }

    @Test
    void controlEventBridgeDoesNotCaptureNonEventSubtype() {
        MassMessage msg = massMessage("demoApp", MessageType.CONTROL, "legacy");
        JsonObject payload = new JsonObject();
        payload.addProperty(WorkerControlEventProtocol.EVENT_FIELD, "crawler.fetch-page");
        msg.setPayload(payload);

        GatewayFrameKind result = frameRouter.route(msg);
        assertEquals(GatewayFrameKind.UNKNOWN, result);
    }

    @Test
    void controlEventBridgeDoesNotCaptureControlEventWithoutGlobalEventCode() {
        MassMessage msg = massMessage("demoApp", MessageType.CONTROL, WorkerControlEventProtocol.SUB_MSG_TYPE);
        msg.setPayload(new JsonObject());

        GatewayFrameKind result = frameRouter.route(msg);
        assertEquals(GatewayFrameKind.UNKNOWN, result);
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
