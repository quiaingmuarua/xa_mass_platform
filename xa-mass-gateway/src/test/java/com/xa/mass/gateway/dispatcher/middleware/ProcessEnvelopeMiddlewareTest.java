package com.xa.mass.gateway.dispatcher.middleware;

import com.xa.mass.base.debug.WorkerDebugMessageStore;
import com.xa.mass.gateway.dispatcher.MessageHandlerRegistry;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
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

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProcessEnvelopeMiddlewareTest {

    private MessageHandlerRegistry handlerRegistry;
    private MessageCodec codec;
    private DispatchRuntimeContext context;
    private EnvelopeMiddleware middleware;

    @BeforeEach
    void setUp() {
        codec = new GsonMessageCodec();
        handlerRegistry = new MessageHandlerRegistry();
        context = mockContext(codec, handlerRegistry);
        middleware = MiddlewareRegistry.processEnvelopeMiddleware();
        WorkerDebugMessageStore.clearAll();
    }

    @Test
    void nullDecodeSkipsHandlingAndReturnsTrue() {
        // Invalid JSON that decodes to null
        Envelope envelope = envelope("not-valid-json-at-all-{{{}}}");
        boolean result = middleware.handle(envelope, context);
        assertTrue(result, "Should return true (continue chain) even when decode fails");
    }

    @Test
    void knownHandlerIsInvokedAndResponseEnqueued() {
        AtomicReference<MassMessage> captured = new AtomicReference<>();
        handlerRegistry.register("proj", MessageType.PING, "hb", msg -> {
            captured.set(msg);
            MassMessage resp = new MassMessage();
            resp.setMsgType(MessageType.PONG);
            resp.setContext(msg.getContext());
            return List.of(resp);
        });

        MassMessage msg = message("proj", MessageType.PING, "hb");
        Envelope envelope = envelope(codec.encode(msg));

        middleware.handle(envelope, context);

        assertNotNull(captured.get(), "Handler should have been called");
        ArgumentCaptor<Envelope> outputCaptor = ArgumentCaptor.forClass(Envelope.class);
        verify(context.getMessageTransporter()).sendOutput(outputCaptor.capture());
        assertNull(outputCaptor.getValue().getEventCode());
    }

    @Test
    void noHandlerWithFallbackEnabledDoesNotSendOutput() {
        handlerRegistry.setEnableFallback(true);
        MassMessage msg = message("proj", MessageType.STATUS, "unknown");
        Envelope envelope = envelope(codec.encode(msg));

        boolean result = middleware.handle(envelope, context);

        assertTrue(result);
        verify(context.getMessageTransporter(), never()).sendOutput(any());
    }

    @Test
    void noHandlerDefaultsToNotFoundWithoutFallback() {
        MassMessage msg = message("proj", MessageType.STATUS, "unknown");
        Envelope envelope = envelope(codec.encode(msg));

        boolean result = middleware.handle(envelope, context);

        assertTrue(result);
        verify(context.getMessageTransporter(), never()).sendOutput(any());
    }

    @Test
    void handlerReturningEmptyResponseDoesNotSendOutput() {
        handlerRegistry.register("p", MessageType.PONG, "hb", msg -> Collections.emptyList());
        MassMessage msg = message("p", MessageType.PONG, "hb");
        Envelope envelope = envelope(codec.encode(msg));

        middleware.handle(envelope, context);

        verify(context.getMessageTransporter(), never()).sendOutput(any());
    }

    @Test
    void responseEnvelopePropagatesCanonicalEventCodeMetadata() {
        handlerRegistry.register("proj", MessageType.CONTROL, "manual-chat", msg -> {
            MassMessage resp = new MassMessage();
            resp.setMsgType(MessageType.CONTROL);
            resp.setSubMsgType("manual-chat");
            resp.setContext(msg.getContext());
            return List.of(resp);
        });

        MassMessage msg = message("proj", MessageType.CONTROL, "manual-chat");
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
    void sendEnvelopeMiddlewareMarksDebugRecordFailedWhenEndpointUnavailable() {
        EnvelopeMiddleware sendMiddleware = MiddlewareRegistry.sendEnvelopeMiddleware();
        DispatchRuntimeContext sendContext = mockContext(codec, handlerRegistry);
        when(sendContext.getSessionManager().sendMessage("worker-1", SessionRoles.TASK_MESSAGES, "{\"hello\":\"world\"}"))
                .thenReturn(false);
        WorkerDebugMessageStore.recordOutbound(
                "worker-1",
                "demoApp",
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

    // ---- helpers ----

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
    private DispatchRuntimeContext mockContext(MessageCodec codec, MessageHandlerRegistry registry) {
        DispatchRuntimeContext ctx = mock(DispatchRuntimeContext.class);
        when(ctx.getMessageCodec()).thenReturn(codec);
        when(ctx.getMessageHandlerRegistry()).thenReturn(registry);
        com.xa.mass.base.channel.tranporter.MessageTransporter<Envelope> transporter =
                mock(com.xa.mass.base.channel.tranporter.MessageTransporter.class);
        when(ctx.getMessageTransporter()).thenReturn(transporter);
        when(ctx.getSessionManager()).thenReturn(mock(com.xa.mass.transport.WorkerEndpointRegistry.class));
        return ctx;
    }
}
