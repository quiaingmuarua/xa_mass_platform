package com.xa.mass.transport.socket.runtime;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.contract.worker.WorkerChannelFrame;
import com.xa.mass.contract.worker.WorkerChannelFrameJsonCodec;
import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.runtime.lease.CurrentSessionDisconnectSink;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportAdapterContribution;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor;
import com.xa.mass.transport.runtime.embedded.AdapterMailboxClient;
import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import com.xa.mass.transport.socket.protocol.SocketTransportFrameCodec;
import com.xa.mass.transport.socket.session.SocketSessionManager;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SocketTransportAdapterBootstrapTest {

    private final WorkerChannelFrameJsonCodec workerFrameCodec = new WorkerChannelFrameJsonCodec();

    @Test
    void enabledAdapterContributesBindingConsumerRawChannelAndServer() {
        SocketAdapterConfig config = enabledConfig();
        SocketTransportAdapterBootstrap bootstrap = new SocketTransportAdapterBootstrap(config);

        TransportAdapterContribution contribution = bootstrap.contribute(context(emptyMailboxClient()));

        assertEquals(1, contribution.getTransportBindings().size());
        TransportBinding binding = contribution.getTransportBindings().get(0);
        assertEquals(SocketAdapterConfig.DEFAULT_ADAPTER_ID, binding.getAdapterId());
        assertEquals("socket-mailbox", binding.getAdapterMailboxKey());
        assertEquals(SocketAdapterConfig.PROTOCOL, binding.getProtocol());
        assertEquals(1, contribution.getAdapterMailboxConsumers().size());
        assertEquals("socket-mailbox", contribution.getAdapterMailboxConsumers().get(0).adapterMailboxKey());
        assertEquals(1, contribution.getRawWorkerMessageChannels().size());
        assertEquals(SocketAdapterConfig.DEFAULT_ADAPTER_ID,
                contribution.getRawWorkerMessageChannels().get(0).adapterId());
        assertEquals(1, contribution.getTransportServers().size());
    }

    @Test
    void disabledAdapterContributesNothingEvenWhenServerIsEnabled() {
        SocketAdapterConfig config = new SocketAdapterConfig();
        config.setEnabled(false);
        config.setServerEnabled(true);
        SocketTransportAdapterBootstrap bootstrap = new SocketTransportAdapterBootstrap(config);

        TransportAdapterContribution contribution = bootstrap.contribute(context(emptyMailboxClient()));

        assertTrue(contribution.getTransportBindings().isEmpty());
        assertTrue(contribution.getAdapterMailboxConsumers().isEmpty());
        assertTrue(contribution.getRawWorkerMessageChannels().isEmpty());
        assertTrue(contribution.getManagedTransportAdapters().isEmpty());
        assertTrue(contribution.getTransportServers().isEmpty());
    }

    @Test
    void contributedCommandExecutorSendsActionFrameToSelectedWorkerSession() {
        StringWriter written = new StringWriter();
        SocketSessionManager sessionManager = sessionManagerWithWorker("worker-1", written);
        AdapterCommandExecutor executor = SocketTransportAdapterBootstrap.socketCommandExecutor(
                sessionManager,
                new SocketTransportFrameCodec()
        );
        DispatchMessage message = dispatchMessage();

        List<DispatchOutcome> outcomes = executor.dispatch(List.of(message));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.DELIVERED, outcomes.get(0).getStatus());
        WorkerChannelFrame frame = workerFrameCodec.decode(written.toString().trim());
        assertEquals(WorkerChannelFrame.ACTION, frame.kind());
        assertEquals(message.payload(), frame.body());
    }

    @Test
    void contributedCommandExecutorReturnsNoEndpointWhenSelectedWorkerHasNoSession() {
        AdapterCommandExecutor executor = SocketTransportAdapterBootstrap.socketCommandExecutor(
                sessionManager(),
                new SocketTransportFrameCodec()
        );

        DispatchOutcome outcome = executor.dispatch(List.of(dispatchMessage())).get(0);

        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, outcome.getStatus());
        assertTrue(outcome.isRetryable());
    }

    private SocketAdapterConfig enabledConfig() {
        SocketAdapterConfig config = new SocketAdapterConfig();
        config.setEnabled(true);
        config.setServerEnabled(true);
        config.setServerPort(0);
        return config;
    }

    private TransportAdapterBootstrapContext context(AdapterMailboxClient adapterMailboxClient) {
        AtomicReference<ResultIngressEntry> captured = new AtomicReference<>();
        return new TransportAdapterBootstrapContext(
                new SocketTransportAdapterBootstrap(new SocketAdapterConfig()).descriptor(),
                "socket-mailbox",
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

    private SocketSessionManager sessionManagerWithWorker(String workerId, StringWriter written) {
        SocketSessionManager sessionManager = sessionManager();
        Socket socket = mock(Socket.class);
        when(socket.isConnected()).thenReturn(true);
        when(socket.isClosed()).thenReturn(false);
        sessionManager.addSession(
                "bucket-1",
                workerId,
                "endpoint-1",
                socket,
                new BufferedWriter(written)
        );
        return sessionManager;
    }

    private SocketSessionManager sessionManager() {
        return new SocketSessionManager(
                "socket",
                "socket",
                AdapterSessionEvidencePublisher.noop("socket", "socket")
        );
    }

    private DispatchMessage dispatchMessage() {
        return new DispatchMessage(
                "delivery-msg-1",
                "worker-1",
                "{\"resultCorrelationRef\":\"corr-msg-1\"}",
                "corr-msg-1",
                0L,
                1L
        );
    }
}
