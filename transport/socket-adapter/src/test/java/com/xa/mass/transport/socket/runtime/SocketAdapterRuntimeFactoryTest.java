package com.xa.mass.transport.socket.runtime;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.base.runtime.RuntimeTaskExecutorStatistics;
import com.xa.mass.contract.worker.WorkerChannelFrame;
import com.xa.mass.contract.worker.WorkerChannelFrameJsonCodec;
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
import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;
import com.xa.mass.transport.runtime.lease.CurrentSessionDisconnectSink;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import com.xa.mass.transport.socket.protocol.SocketTransportFrameCodec;
import com.xa.mass.transport.socket.session.SocketSessionManager;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SocketAdapterRuntimeFactoryTest {

    private final WorkerChannelFrameJsonCodec workerFrameCodec = new WorkerChannelFrameJsonCodec();

    @Test
    void runtimeFactoryCreatesBindingForEnabledAdapter() {
        SocketAdapterConfig config = enabledConfig();
        SocketAdapterRuntimeFactory factory = new SocketAdapterRuntimeFactory();

        EmbeddedTransportAdapterRuntime runtime = factory.create(spec(config), environment());

        TransportBinding binding = runtime.binding();
        assertEquals(SocketAdapterConfig.DEFAULT_ADAPTER_ID, binding.getAdapterId());
        assertEquals(SocketAdapterConfig.DEFAULT_ADAPTER_ID, binding.getAdapterMailboxKey());
        assertEquals(SocketAdapterConfig.PROTOCOL, binding.getProtocol());
    }

    @Test
    void commandExecutorSendsActionFrameToSelectedWorkerSession() {
        StringWriter written = new StringWriter();
        SocketSessionManager sessionManager = sessionManagerWithWorker("worker-1", written);
        AdapterCommandExecutor executor = SocketAdapterRuntimeFactory.socketCommandExecutor(
                sessionManager,
                new SocketTransportFrameCodec()
        );
        DispatchMessage message = dispatchMessage();

        List<DispatchOutcome> outcomes = executor.dispatch(List.of(message));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.DELIVERED, outcomes.getFirst().getStatus());
        WorkerChannelFrame frame = workerFrameCodec.decode(written.toString().trim());
        assertEquals(WorkerChannelFrame.ACTION, frame.kind());
        assertEquals(message.payload(), frame.body());
    }

    @Test
    void commandExecutorReturnsNoEndpointWhenSelectedWorkerHasNoSession() {
        AdapterCommandExecutor executor = SocketAdapterRuntimeFactory.socketCommandExecutor(
                sessionManager(),
                new SocketTransportFrameCodec()
        );

        DispatchOutcome outcome = executor.dispatch(List.of(dispatchMessage())).getFirst();

        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, outcome.getStatus());
        assertTrue(outcome.isRetryable());
    }

    private SocketAdapterConfig enabledConfig() {
        SocketAdapterConfig config = new SocketAdapterConfig();
        config.setEnabled(true);
        config.setServerEnabled(false);
        return config;
    }

    private EmbeddedAdapterRuntimeSpec spec(SocketAdapterConfig config) {
        return new EmbeddedAdapterRuntimeSpec(
                SocketAdapterRuntimeFactory.TYPE,
                config.getAdapterId(),
                config.getAdapterId(),
                TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY,
                SocketAdapterRuntimeFactory.options(config)
        );
    }

    private EmbeddedAdapterRuntimeEnvironment environment() {
        return new EmbeddedAdapterRuntimeEnvironment(
                new InMemoryTransportDispatchHandoff(10),
                new InMemoryTransportResultIngressQueue(10),
                new InMemoryTransportEndpointLeaseStore(),
                CurrentSessionDisconnectSink.NOOP,
                new DirectExecutor()
        );
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
                AdapterSessionEvidencePublisher.noop("socket")
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
