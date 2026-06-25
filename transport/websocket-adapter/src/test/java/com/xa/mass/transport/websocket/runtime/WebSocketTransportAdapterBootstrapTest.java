package com.xa.mass.transport.websocket.runtime;

import com.google.gson.JsonObject;
import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.contract.worker.WorkerChannelFrame;
import com.xa.mass.contract.worker.WorkerChannelFrameJsonCodec;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.runtime.lease.CurrentSessionDisconnectSink;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.runtime.TransportAdapterContribution;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor;
import com.xa.mass.transport.runtime.embedded.AdapterMailboxClient;
import com.xa.mass.transport.runtime.frame.TransportJsonFrameParser;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;
import com.xa.mass.transport.websocket.session.WebSocketSessionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketTransportAdapterBootstrapTest {

    private final WorkerChannelFrameJsonCodec frameCodec = new WorkerChannelFrameJsonCodec();
    private final TransportJsonFrameParser frameParser = new TransportJsonFrameParser();

    @Test
    void enabledAdapterContributesBindingConsumerRawChannelServerAndRefresher() {
        AtomicReference<ResultIngressEntry> captured = new AtomicReference<>();
        AtomicReference<WebSocketServerFactoryContext> serverContext = new AtomicReference<>();
        WebSocketTransportAdapterBootstrap bootstrap = bootstrapCapturing(serverContext);

        TransportAdapterContribution contribution = bootstrap.contribute(context(captured, emptyMailboxClient()));

        assertEquals(1, contribution.getTransportBindings().size());
        TransportBinding binding = contribution.getTransportBindings().get(0);
        assertEquals(WebSocketAdapterConfig.DEFAULT_ADAPTER_ID, binding.getAdapterId());
        assertEquals("websocket-mailbox", binding.getAdapterMailboxKey());
        assertEquals(WebSocketAdapterConfig.PROTOCOL, binding.getProtocol());
        assertEquals(1, contribution.getAdapterMailboxConsumers().size());
        assertEquals("websocket-mailbox", contribution.getAdapterMailboxConsumers().get(0).adapterMailboxKey());
        assertEquals(1, contribution.getRawWorkerMessageChannels().size());
        assertEquals(WebSocketAdapterConfig.DEFAULT_ADAPTER_ID,
                contribution.getRawWorkerMessageChannels().get(0).adapterId());
        assertEquals(1, contribution.getManagedTransportAdapters().size());
        assertEquals(1, contribution.getTransportServers().size());
        assertNotNull(serverContext.get());
    }

    @Test
    void disabledAdapterContributesNothingEvenWhenServerIsEnabled() {
        AtomicReference<ResultIngressEntry> captured = new AtomicReference<>();
        AtomicReference<WebSocketServerFactoryContext> serverContext = new AtomicReference<>();
        WebSocketAdapterConfig config = new WebSocketAdapterConfig();
        config.setEnabled(false);
        config.setServerEnabled(true);
        TransportServerFactory<WebSocketServerFactoryContext> factory = context -> {
            serverContext.set(context);
            return noopServer();
        };
        WebSocketTransportAdapterBootstrap bootstrap = new WebSocketTransportAdapterBootstrap(config, factory);

        TransportAdapterContribution contribution = bootstrap.contribute(context(captured, emptyMailboxClient()));

        assertTrue(contribution.getTransportBindings().isEmpty());
        assertTrue(contribution.getAdapterMailboxConsumers().isEmpty());
        assertTrue(contribution.getRawWorkerMessageChannels().isEmpty());
        assertTrue(contribution.getManagedTransportAdapters().isEmpty());
        assertTrue(contribution.getTransportServers().isEmpty());
        assertNull(serverContext.get());
    }

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

    @Test
    void contributedCommandExecutorSendsActionFrameToSelectedWorkerSession() {
        SessionFixture fixture = sessionWithWorker("worker-1");
        DispatchMessage message = dispatchMessage();
        AdapterCommandExecutor executor =
                WebSocketTransportAdapterBootstrap.webSocketCommandExecutor(fixture.registry(), frameCodec);

        List<DispatchOutcome> outcomes = executor.dispatch(List.of(message));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.DELIVERED, outcomes.get(0).getStatus());
        ArgumentCaptor<TextWebSocketFrame> captor = ArgumentCaptor.forClass(TextWebSocketFrame.class);
        verify(fixture.channel()).writeAndFlush(captor.capture());
        JsonObject frame = frameParser.parseObject(captor.getValue().text());
        assertEquals(WorkerChannelFrame.ACTION, frame.get("kind").getAsString());
        assertEquals(message.payload(), frame.get("body").getAsString());
        fixture.registry().shutdown();
    }

    @Test
    void contributedCommandExecutorReturnsNoEndpointWhenSelectedWorkerHasNoSession() {
        AdapterSessionEvidencePublisher sessionEvidencePublisher =
                AdapterSessionEvidencePublisher.noop("websocket", "websocket");
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry(sessionEvidencePublisher);
        AdapterCommandExecutor executor =
                WebSocketTransportAdapterBootstrap.webSocketCommandExecutor(registry, frameCodec);

        DispatchOutcome outcome = executor.dispatch(List.of(dispatchMessage())).get(0);

        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, outcome.getStatus());
        assertTrue(outcome.isRetryable());
        registry.shutdown();
    }

    private WebSocketTransportAdapterBootstrap bootstrapCapturing(
        AtomicReference<WebSocketServerFactoryContext> serverContext) {
        WebSocketAdapterConfig config = new WebSocketAdapterConfig();
        TransportServerFactory<WebSocketServerFactoryContext> factory = context -> {
            serverContext.set(context);
            return noopServer();
        };
        return new WebSocketTransportAdapterBootstrap(config, factory);
    }

    private SessionFixture sessionWithWorker(String workerId) {
        AdapterSessionEvidencePublisher sessionEvidencePublisher =
                AdapterSessionEvidencePublisher.noop("websocket", "websocket");
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry(sessionEvidencePublisher);
        Channel channel = mockActiveChannel(workerId);
        registry.addSession("bucket-1", workerId, channel);
        return new SessionFixture(registry, channel);
    }

    private Channel mockActiveChannel(String idText) {
        Channel ch = mock(Channel.class);
        ChannelId chId = mock(ChannelId.class);
        when(chId.asShortText()).thenReturn(idText);
        when(ch.id()).thenReturn(chId);
        when(ch.isActive()).thenReturn(true);
        return ch;
    }

    private record SessionFixture(WebSocketSessionRegistry registry, Channel channel) {
    }

    private TransportAdapterBootstrapContext context(AtomicReference<ResultIngressEntry> captured) {
        return context(captured, null);
    }

    private TransportAdapterBootstrapContext context(AtomicReference<ResultIngressEntry> captured,
                                                     AdapterMailboxClient adapterMailboxClient) {
        return new TransportAdapterBootstrapContext(
                new WebSocketTransportAdapterBootstrap(new WebSocketAdapterConfig()).descriptor(),
                "websocket-mailbox",
                entry -> {
                    captured.set(entry);
                    return true;
                },
                new InMemoryTransportEndpointLeaseStore(),
                CurrentSessionDisconnectSink.NOOP,
                mock(RuntimeTaskExecutor.class),
                adapterMailboxClient,
                null,
                null,
                1_000L
        );
    }

    private AdapterMailboxClient emptyMailboxClient() {
        return (adapterMailboxKey, maxItems, timeoutMillis) -> List.of();
    }

    private String actionReplyFrame(String frameId, String replyRef) {
        return frameCodec.encode(new WorkerChannelFrame(
                frameId,
                WorkerChannelFrame.ACTION_REPLY,
                "{\"replyRef\":\"" + replyRef + "\",\"body\":\"ok\"}"
        ));
    }

    private DispatchMessage dispatchMessage() {
        return new DispatchMessage(
                "delivery-msg-1",
                "worker-1",
                "{\"messageId\":\"msg-1\",\"eventCode\":\"crawler.fetch-page\"}",
                "corr-msg-1",
                0L,
                1L
        );
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
