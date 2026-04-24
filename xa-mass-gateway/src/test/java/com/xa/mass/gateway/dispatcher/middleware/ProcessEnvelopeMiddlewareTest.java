package com.xa.mass.gateway.dispatcher.middleware;

import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.base.debug.WorkerDebugMessageStore;
import com.xa.mass.gateway.dispatcher.GatewayFrameRouter;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.dispatcher.port.ControlEventRequestFrameBridge;
import com.xa.mass.gateway.dispatcher.port.ControlEventResponseFrameSink;
import com.xa.mass.gateway.dispatcher.port.TaskStepFrameBridge;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.gateway.queue.GsonMessageCodec;
import com.xa.mass.gateway.queue.MessageCodec;
import com.xa.mass.gateway.session.SessionRoles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProcessEnvelopeMiddlewareTest {

    private GatewayFrameRouter frameRouter;
    private MessageCodec codec;
    private DispatchRuntimeContext context;
    private EnvelopeMiddleware middleware;

    @BeforeEach
    void setUp() {
        codec = new GsonMessageCodec();
        frameRouter = new GatewayFrameRouter();
        context = mockContext(codec, frameRouter);
        middleware = new MiddlewareRegistry().getInputMiddlewares().get(0);
        WorkerDebugMessageStore.clearAll();
    }

    @Test
    void nullDecodeSkipsHandlingAndReturnsTrue() {
        Envelope envelope = envelope("not-valid-json-at-all-{{{}}}");
        boolean result = middleware.handle(envelope, context);
        assertTrue(result);
    }

    @Test
    void controlEventRequestBridgeIsInvokedAndResponseEnqueued() {
        AtomicReference<MassMessage> captured = new AtomicReference<>();
        when(context.getControlEventRequestFrameBridge()).thenReturn(msg -> {
            captured.set(msg);
            MassMessage resp = new MassMessage();
            resp.setMsgType(MessageType.CONTROL);
            resp.setSubMsgType(WorkerControlEventProtocol.SUB_MSG_TYPE);
            resp.setContext(msg.getContext());
            return List.of(resp);
        });

        MassMessage msg = controlEventRequest("proj", "mock.state.get");
        Envelope envelope = envelope(codec.encode(msg));

        middleware.handle(envelope, context);

        assertNotNull(captured.get());
        ArgumentCaptor<Envelope> outputCaptor = ArgumentCaptor.forClass(Envelope.class);
        verify(context.getMessageTransporter()).sendOutput(outputCaptor.capture());
        assertNull(outputCaptor.getValue().getEventCode());
    }

    @Test
    void noHandlerDefaultsToNotFound() {
        MassMessage msg = message("proj", MessageType.CONTROL, "unknown");
        Envelope envelope = envelope(codec.encode(msg));

        boolean result = middleware.handle(envelope, context);

        assertTrue(result);
        verify(context.getMessageTransporter(), never()).sendOutput(any());
    }

    @Test
    void controlEventRequestBridgeReturningEmptyResponseDoesNotSendOutput() {
        when(context.getControlEventRequestFrameBridge()).thenReturn(msg -> List.of());
        MassMessage msg = controlEventRequest("p", "mock.state.get");
        Envelope envelope = envelope(codec.encode(msg));

        middleware.handle(envelope, context);

        verify(context.getMessageTransporter(), never()).sendOutput(any());
    }

    @Test
    void responseEnvelopePropagatesCanonicalEventCodeMetadata() {
        when(context.getControlEventRequestFrameBridge()).thenReturn(msg -> {
            MassMessage resp = new MassMessage();
            resp.setMsgType(MessageType.CONTROL);
            resp.setSubMsgType(WorkerControlEventProtocol.SUB_MSG_TYPE);
            resp.setContext(msg.getContext());
            return List.of(resp);
        });

        MassMessage msg = controlEventRequest("proj", "mock.state.get");
        Envelope envelope = Envelope.builder()
                .rawJson(codec.encode(msg))
                .workerId("worker-1")
                .connRole(SessionRoles.TASK_MESSAGES)
                .eventCode("mock.state.get")
                .build();

        middleware.handle(envelope, context);

        ArgumentCaptor<Envelope> outputCaptor = ArgumentCaptor.forClass(Envelope.class);
        verify(context.getMessageTransporter()).sendOutput(outputCaptor.capture());
        assertEquals("mock.state.get", outputCaptor.getValue().getEventCode());
    }

    @Test
    void taskStepResponsesDoNotBackfillCanonicalEventCodeWhenInboundEnvelopeHasNone() {
        when(context.getTaskStepFrameBridge()).thenReturn(new DerivedTaskStepBridge());

        MassMessage msg = message("proj", MessageType.TASK, "step");
        Envelope envelope = Envelope.builder()
                .rawJson(codec.encode(msg))
                .workerId("worker-1")
                .connRole(SessionRoles.TASK_MESSAGES)
                .build();

        middleware.handle(envelope, context);

        ArgumentCaptor<Envelope> outputCaptor = ArgumentCaptor.forClass(Envelope.class);
        verify(context.getMessageTransporter()).sendOutput(outputCaptor.capture());
        assertNull(outputCaptor.getValue().getEventCode());
    }

    @Test
    void controlEventResponseSinkConsumesWithoutOutput() {
        AtomicReference<MassMessage> captured = new AtomicReference<>();
        when(context.getControlEventResponseFrameSink()).thenReturn(captured::set);

        MassMessage msg = message("proj", MessageType.CONTROL, WorkerControlEventProtocol.SUB_MSG_TYPE);
        msg.setResponse(true);
        Envelope envelope = envelope(codec.encode(msg));

        boolean result = middleware.handle(envelope, context);

        assertTrue(result);
        assertNotNull(captured.get());
        verify(context.getMessageTransporter(), never()).sendOutput(any());
    }

    @Test
    void sendEnvelopeMiddlewareMarksDebugRecordFailedWhenEndpointUnavailable() {
        EnvelopeMiddleware sendMiddleware = new MiddlewareRegistry().getOutputMiddlewares().get(0);
        DispatchRuntimeContext sendContext = mockContext(codec, frameRouter);
        when(sendContext.getSessionManager().sendMessage("worker-1", SessionRoles.TASK_MESSAGES, "{\"hello\":\"world\"}"))
                .thenReturn(false);
        WorkerDebugMessageStore.recordOutbound(
                "worker-1",
                "demoApp",
                "mock.state.get",
                "CONTROL",
                "event",
                "trace-1",
                "{\"event\":\"mock.state.get\"}",
                "{\"msgId\":\"trace-1\"}",
                "queued"
        );

        boolean result = sendMiddleware.handle(
                Envelope.builder()
                        .workerId("worker-1")
                        .connRole(SessionRoles.TASK_MESSAGES)
                        .traceId("trace-1")
                        .rawJson("{\"hello\":\"world\"}")
                        .build(),
                sendContext
        );

        assertFalse(result);
        assertEquals("FAILED", WorkerDebugMessageStore.getHistory("worker-1").get(0).getStatus());
    }

    private Envelope envelope(String rawJson) {
        return Envelope.builder().rawJson(rawJson).workerId("worker-1").connRole(SessionRoles.TASK_MESSAGES).build();
    }

    private MassMessage message(String project, MessageType type, String subType) {
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

    @SuppressWarnings("unchecked")
    private DispatchRuntimeContext mockContext(MessageCodec codec, GatewayFrameRouter frameRouter) {
        DispatchRuntimeContext ctx = mock(DispatchRuntimeContext.class);
        when(ctx.getMessageCodec()).thenReturn(codec);
        when(ctx.getFrameRouter()).thenReturn(frameRouter);
        com.xa.mass.base.channel.tranporter.MessageTransporter<Envelope> transporter =
                mock(com.xa.mass.base.channel.tranporter.MessageTransporter.class);
        when(ctx.getMessageTransporter()).thenReturn(transporter);
        when(ctx.getSessionManager()).thenReturn(mock(com.xa.mass.transport.WorkerEndpointRegistry.class));
        return ctx;
    }

    private MassMessage controlEventRequest(String project, String eventCode) {
        MassMessage msg = message(project, MessageType.CONTROL, WorkerControlEventProtocol.SUB_MSG_TYPE);
        com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
        payload.addProperty(WorkerControlEventProtocol.EVENT_FIELD, eventCode);
        msg.setPayload(payload);
        return msg;
    }

    private static final class DerivedTaskStepBridge implements TaskStepFrameBridge {

        @Override
        public List<MassMessage> handleTaskStep(MassMessage msg) {
            MassMessage resp = new MassMessage();
            resp.setMsgType(MessageType.TASK);
            resp.setSubMsgType("step");
            resp.setContext(msg.getContext());
            return List.of(resp);
        }
    }
}
