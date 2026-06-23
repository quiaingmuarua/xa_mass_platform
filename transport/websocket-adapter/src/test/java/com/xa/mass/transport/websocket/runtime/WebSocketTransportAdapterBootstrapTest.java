package com.xa.mass.transport.websocket.runtime;

import com.google.gson.JsonObject;
import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.contract.worker.WorkerChannelFrame;
import com.xa.mass.contract.worker.WorkerChannelFrameJsonCodec;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.frame.TransportJsonFrameParser;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class WebSocketTransportAdapterBootstrapTest {

    private final WorkerChannelFrameJsonCodec frameCodec = new WorkerChannelFrameJsonCodec();
    private final TransportJsonFrameParser frameParser = new TransportJsonFrameParser();

    @Test
    void actionReplyFrameReachesResultIngressThroughBootstrapSink() {
        AtomicReference<ResultIngressEntry> captured = new AtomicReference<>();
        AtomicReference<WebSocketServerFactoryContext> serverContext = new AtomicReference<>();
        WebSocketTransportAdapterBootstrap bootstrap = bootstrapCapturing(serverContext);

        bootstrap.contribute(context(captured));
        serverContext.get().acceptInboundRawFrame(actionReplyFrame("frame-1", "reply-1"));

        ResultIngressEntry entry = captured.get();
        assertNotNull(entry);
        assertEquals("reply-1", entry.partitionKey());
        assertEquals("reply-1", entry.message().resultCorrelationRef());
        assertEquals("{\"replyRef\":\"reply-1\",\"body\":\"ok\"}", entry.message().payload());
        assertEquals(WebSocketAdapterConfig.DEFAULT_ADAPTER_ID, entry.diagnostics().get("adapterId"));
    }

    @Test
    void websocketControlFrameIsClassifiedBeforeSharedResultReader() {
        AtomicReference<ResultIngressEntry> captured = new AtomicReference<>();
        AtomicReference<WebSocketServerFactoryContext> serverContext = new AtomicReference<>();
        WebSocketTransportAdapterBootstrap bootstrap = bootstrapCapturing(serverContext);
        String mixedFrame = withType(actionReplyFrame("frame-1", "reply-1"), "heartbeat");

        bootstrap.contribute(context(captured));
        serverContext.get().acceptInboundRawFrame(mixedFrame);

        assertNull(captured.get());
    }

    private WebSocketTransportAdapterBootstrap bootstrapCapturing(
            AtomicReference<WebSocketServerFactoryContext> serverContext) {
        WebSocketAdapterConfig config = new WebSocketAdapterConfig();
        config.setTransportServerFactory(context -> {
            serverContext.set(context);
            return noopServer();
        });
        return new WebSocketTransportAdapterBootstrap(config);
    }

    private TransportAdapterBootstrapContext context(AtomicReference<ResultIngressEntry> captured) {
        WorkerPresenceIngress presenceIngress = new WorkerPresenceIngress() {
            @Override
            public void sessionConnected(com.xa.mass.transport.channel.WorkerSessionPresenceEvent event) {
            }

            @Override
            public void sessionHeartbeat(com.xa.mass.transport.channel.WorkerSessionPresenceEvent event) {
            }

            @Override
            public void sessionDisconnected(com.xa.mass.transport.channel.WorkerSessionPresenceEvent event) {
            }
        };
        return new TransportAdapterBootstrapContext(
                new WebSocketTransportAdapterBootstrap(new WebSocketAdapterConfig()).descriptor(),
                "websocket-mailbox",
                entry -> {
                    captured.set(entry);
                    return true;
                },
                presenceIngress,
                new InMemoryTransportEndpointLeaseStore(),
                mock(RuntimeTaskExecutor.class),
                null,
                null,
                null,
                1_000L
        );
    }

    private String actionReplyFrame(String frameId, String replyRef) {
        return frameCodec.encode(new WorkerChannelFrame(
                frameId,
                WorkerChannelFrame.ACTION_REPLY,
                "{\"replyRef\":\"" + replyRef + "\",\"body\":\"ok\"}"
        ));
    }

    private String withType(String rawFrame, String type) {
        JsonObject frame = frameParser.parseObject(rawFrame);
        frame.addProperty("type", type);
        return frameParser.toJson(frame);
    }

    private TransportServer noopServer() {
        return new TransportServer() {
            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }

            @Override
            public boolean isRunning() {
                return true;
            }
        };
    }
}
