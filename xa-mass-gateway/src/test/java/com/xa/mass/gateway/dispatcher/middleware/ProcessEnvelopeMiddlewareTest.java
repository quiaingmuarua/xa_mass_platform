package com.xa.mass.gateway.dispatcher.middleware;

import com.xa.mass.gateway.dispatcher.MessageHandlerRegistry;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.gateway.queue.GsonMessageCodec;
import com.xa.mass.gateway.queue.MessageCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
        verify(context.getMessageTransporter()).sendOutput(any(Envelope.class));
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
    void handlerReturningEmptyResponseDoesNotSendOutput() {
        handlerRegistry.register("p", MessageType.PONG, "hb", msg -> Collections.emptyList());
        MassMessage msg = message("p", MessageType.PONG, "hb");
        Envelope envelope = envelope(codec.encode(msg));

        middleware.handle(envelope, context);

        verify(context.getMessageTransporter(), never()).sendOutput(any());
    }

    // ---- helpers ----

    private Envelope envelope(String rawJson) {
        return Envelope.builder().rawJson(rawJson).deviceId("dev-1").connRole("task").build();
    }

    private MassMessage message(String project, MessageType type, String subType) {
        MassMessage msg = new MassMessage();
        msg.setProject(project);
        msg.setMsgType(type);
        msg.setSubMsgType(subType);
        MessageContext ctx = new MessageContext();
        ctx.setDeviceId("dev-1");
        ctx.setConnRole("task");
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
        return ctx;
    }
}
