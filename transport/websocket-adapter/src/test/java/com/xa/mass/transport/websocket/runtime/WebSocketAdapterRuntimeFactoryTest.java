package com.xa.mass.transport.websocket.runtime;

import com.google.gson.JsonObject;
import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.base.runtime.RuntimeTaskExecutorStatistics;
import com.xa.mass.contract.worker.WorkerChannelFrame;
import com.xa.mass.contract.worker.WorkerChannelFrameJsonCodec;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.runtime.InMemoryTransportResultIngressQueue;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.TransportResultIngressQueue;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;
import com.xa.mass.transport.runtime.delivery.InMemoryTransportDispatchHandoff;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor;
import com.xa.mass.transport.runtime.embedded.EmbeddedAdapterRuntimeEnvironment;
import com.xa.mass.transport.runtime.embedded.EmbeddedAdapterRuntimeSpec;
import com.xa.mass.transport.runtime.embedded.EmbeddedTransportAdapterRuntime;
import com.xa.mass.transport.runtime.frame.TransportJsonFrameParser;
import com.xa.mass.transport.runtime.lease.CurrentSessionDisconnectSink;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;
import com.xa.mass.transport.websocket.session.WebSocketSessionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketAdapterRuntimeFactoryTest {

    private final WorkerChannelFrameJsonCodec frameCodec = new WorkerChannelFrameJsonCodec();
    private final TransportJsonFrameParser frameParser = new TransportJsonFrameParser();

    @Test
    void runtimeFactoryCreatesBindingServerAndResultIngressPath() throws Exception {
        AtomicReference<WebSocketServerFactoryContext> serverContext = new AtomicReference<>();
        InMemoryTransportResultIngressQueue resultQueue = new InMemoryTransportResultIngressQueue(10);
        WebSocketAdapterRuntimeFactory factory = new WebSocketAdapterRuntimeFactory(Map.of(
                WebSocketAdapterConfig.DEFAULT_ADAPTER_ID,
                context -> {
                    serverContext.set(context);
                    return noopServer();
                }
        ));

        EmbeddedTransportAdapterRuntime runtime = factory.create(spec(new WebSocketAdapterConfig()), environment(resultQueue));

        TransportBinding binding = runtime.binding();
        assertEquals(WebSocketAdapterConfig.DEFAULT_ADAPTER_ID, binding.getAdapterId());
        assertEquals(WebSocketAdapterConfig.DEFAULT_ADAPTER_ID, binding.getAdapterMailboxKey());
        assertEquals(WebSocketAdapterConfig.PROTOCOL, binding.getProtocol());
        assertNotNull(serverContext.get());

        serverContext.get().acceptInboundRawFrame(actionReplyFrame("frame-1", "reply-1"));
        ResultIngressEntry entry = resultQueue.poll(TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY, 10L);
        assertNotNull(entry);
        assertEquals("reply-1", entry.partitionKey());
        assertEquals("reply-1", entry.message().resultCorrelationRef());
        assertEquals("{\"replyRef\":\"reply-1\",\"body\":\"ok\"}", entry.message().payload());
    }

    @Test
    void controlFrameDoesNotEnterResultIngress() throws Exception {
        AtomicReference<WebSocketServerFactoryContext> serverContext = new AtomicReference<>();
        InMemoryTransportResultIngressQueue resultQueue = new InMemoryTransportResultIngressQueue(10);
        WebSocketAdapterRuntimeFactory factory = new WebSocketAdapterRuntimeFactory(Map.of(
                WebSocketAdapterConfig.DEFAULT_ADAPTER_ID,
                context -> {
                    serverContext.set(context);
                    return noopServer();
                }
        ));

        factory.create(spec(new WebSocketAdapterConfig()), environment(resultQueue));
        serverContext.get().acceptInboundRawFrame(withType(actionReplyFrame("frame-1", "reply-1"), "heartbeat"));

        assertNull(resultQueue.poll(TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY, 10L));
    }

    @Test
    void commandExecutorSendsActionFrameToSelectedWorkerSession() {
        SessionFixture fixture = sessionWithWorker("worker-1");
        DispatchMessage message = dispatchMessage();
        AdapterCommandExecutor executor =
                WebSocketAdapterRuntimeFactory.webSocketCommandExecutor(fixture.registry(), frameCodec);

        List<DispatchOutcome> outcomes = executor.dispatch(List.of(message));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.DELIVERED, outcomes.getFirst().getStatus());
        ArgumentCaptor<TextWebSocketFrame> captor = ArgumentCaptor.forClass(TextWebSocketFrame.class);
        verify(fixture.channel()).writeAndFlush(captor.capture());
        JsonObject frame = frameParser.parseObject(captor.getValue().text());
        assertEquals(WorkerChannelFrame.ACTION, frame.get("kind").getAsString());
        assertEquals(message.payload(), frame.get("body").getAsString());
        fixture.registry().shutdown();
    }

    @Test
    void commandExecutorReturnsNoEndpointWhenSelectedWorkerHasNoSession() {
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry(
                AdapterSessionEvidencePublisher.noop("websocket"));
        AdapterCommandExecutor executor =
                WebSocketAdapterRuntimeFactory.webSocketCommandExecutor(registry, frameCodec);

        DispatchOutcome outcome = executor.dispatch(List.of(dispatchMessage())).getFirst();

        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, outcome.getStatus());
        assertTrue(outcome.isRetryable());
        registry.shutdown();
    }

    private EmbeddedAdapterRuntimeSpec spec(WebSocketAdapterConfig config) {
        return new EmbeddedAdapterRuntimeSpec(
                WebSocketAdapterRuntimeFactory.TYPE,
                config.getAdapterId(),
                config.getAdapterId(),
                TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY,
                WebSocketAdapterRuntimeFactory.options(config)
        );
    }

    private EmbeddedAdapterRuntimeEnvironment environment(TransportResultIngressQueue resultQueue) {
        return new EmbeddedAdapterRuntimeEnvironment(
                new InMemoryTransportDispatchHandoff(10),
                resultQueue,
                new InMemoryTransportEndpointLeaseStore(),
                CurrentSessionDisconnectSink.NOOP,
                new DirectExecutor()
        );
    }

    private SessionFixture sessionWithWorker(String workerId) {
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry(
                AdapterSessionEvidencePublisher.noop("websocket"));
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

    private record SessionFixture(WebSocketSessionRegistry registry, Channel channel) {
    }

    private static final class DirectExecutor implements RuntimeTaskExecutor {
        @Override
        public Future<?> submit(Runnable task) {
            task.run();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            try {
                return CompletableFuture.completedFuture(task.call());
            } catch (Exception e) {
                CompletableFuture<T> failed = new CompletableFuture<>();
                failed.completeExceptionally(e);
                return failed;
            }
        }

        @Override
        public void shutdown() {
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public RuntimeTaskExecutorStatistics getStatistics() {
            return new RuntimeTaskExecutorStatistics(0, 0, 0, 0, 0, 1);
        }
    }
}
