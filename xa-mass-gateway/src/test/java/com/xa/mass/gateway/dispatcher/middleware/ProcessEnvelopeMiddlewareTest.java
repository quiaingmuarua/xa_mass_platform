package com.xa.mass.gateway.dispatcher.middleware;

import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.base.debug.WorkerDebugMessageStore;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.gateway.dispatcher.DispatcherContext;
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
import com.xa.mass.transport.WorkerEndpointRegistry;
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
    private MessageTransporter<Envelope> transporter;
    private WorkerEndpointRegistry endpointRegistry;
    private EnvelopeMiddleware middleware;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        codec = new GsonMessageCodec();
        frameRouter = new GatewayFrameRouter();
        transporter = mock(MessageTransporter.class);
        endpointRegistry = mock(WorkerEndpointRegistry.class);
        context = createContext(null, null, null);
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
        context = createContext(null, msg -> {
            captured.set(msg);
            MassMessage resp = new MassMessage();
            resp.setMsgType(MessageType.CONTROL);
            resp.setSubMsgType(WorkerControlEventProtocol.SUB_MSG_TYPE);
            resp.setContext(msg.getContext());
            return List.of(resp);
        }, null);

        MassMessage msg = controlEventRequest("proj", "mock.state.get");
        Envelope envelope = envelope(codec.encode(msg));

        middleware.handle(envelope, context);

        assertNotNull(captured.get());
        ArgumentCaptor<Envelope> outputCaptor = ArgumentCaptor.forClass(Envelope.class);
        verify(transporter).sendOutput(outputCaptor.capture());
        assertNull(outputCaptor.getValue().getEventCode());
    }

    @Test
    void noHandlerDefaultsToNotFound() {
        MassMessage msg = message("proj", MessageType.CONTROL, "unknown");
        Envelope envelope = envelope(codec.encode(msg));

        boolean result = middleware.handle(envelope, context);

        assertTrue(result);
        verify(transporter, never()).sendOutput(any());
    }

    @Test
    void controlEventRequestBridgeReturningEmptyResponseDoesNotSendOutput() {
        context = createContext(null, msg -> List.of(), null);
        MassMessage msg = controlEventRequest("p", "mock.state.get");
        Envelope envelope = envelope(codec.encode(msg));

        middleware.handle(envelope, context);

        verify(transporter, never()).sendOutput(any());
    }

    @Test
    void responseEnvelopePropagatesCanonicalEventCodeMetadata() {
        context = createContext(null, msg -> {
            MassMessage resp = new MassMessage();
            resp.setMsgType(MessageType.CONTROL);
            resp.setSubMsgType(WorkerControlEventProtocol.SUB_MSG_TYPE);
            resp.setContext(msg.getContext());
            return List.of(resp);
        }, null);

        MassMessage msg = controlEventRequest("proj", "mock.state.get");
        Envelope envelope = Envelope.builder()
                .rawJson(codec.encode(msg))
                .workerId("worker-1")
                .connRole(SessionRoles.TASK_MESSAGES)
                .eventCode("mock.state.get")
                .build();

        middleware.handle(envelope, context);

        ArgumentCaptor<Envelope> outputCaptor = ArgumentCaptor.forClass(Envelope.class);
        verify(transporter).sendOutput(outputCaptor.capture());
        assertEquals("mock.state.get", outputCaptor.getValue().getEventCode());
    }

    @Test
    void taskStepResponsesDoNotBackfillCanonicalEventCodeWhenInboundEnvelopeHasNone() {
        context = createContext(new DerivedTaskStepBridge(), null, null);

        MassMessage msg = message("proj", MessageType.TASK, "step");
        Envelope envelope = Envelope.builder()
                .rawJson(codec.encode(msg))
                .workerId("worker-1")
                .connRole(SessionRoles.TASK_MESSAGES)
                .build();

        middleware.handle(envelope, context);

        ArgumentCaptor<Envelope> outputCaptor = ArgumentCaptor.forClass(Envelope.class);
        verify(transporter).sendOutput(outputCaptor.capture());
        assertNull(outputCaptor.getValue().getEventCode());
    }

    @Test
    void controlEventResponseSinkConsumesWithoutOutput() {
        AtomicReference<MassMessage> captured = new AtomicReference<>();
        context = createContext(null, null, captured::set);

        MassMessage msg = message("proj", MessageType.CONTROL, WorkerControlEventProtocol.SUB_MSG_TYPE);
        msg.setResponse(true);
        Envelope envelope = envelope(codec.encode(msg));

        boolean result = middleware.handle(envelope, context);

        assertTrue(result);
        assertNotNull(captured.get());
        verify(transporter, never()).sendOutput(any());
    }

    @Test
    void sendEnvelopeMiddlewareMarksDebugRecordFailedWhenEndpointUnavailable() {
        EnvelopeMiddleware sendMiddleware = new MiddlewareRegistry().getOutputMiddlewares().get(0);
        when(endpointRegistry.sendMessage("worker-1", SessionRoles.TASK_MESSAGES, "{\"hello\":\"world\"}"))
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
                context
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

    private DispatchRuntimeContext createContext(TaskStepFrameBridge taskStepFrameBridge,
                                                 ControlEventRequestFrameBridge controlEventRequestFrameBridge,
                                                 ControlEventResponseFrameSink controlEventResponseFrameSink) {
        return new DispatcherContext(
                transporter,
                endpointRegistry,
                codec,
                frameRouter,
                taskStepFrameBridge,
                controlEventRequestFrameBridge,
                controlEventResponseFrameSink
        );
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
