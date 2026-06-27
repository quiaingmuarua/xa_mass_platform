package com.xa.mass.transport.starter;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.base.runtime.RuntimeTaskExecutorStatistics;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.runtime.InMemoryTransportResultIngressQueue;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.TransportResultIngressQueue;
import com.xa.mass.transport.runtime.delivery.InMemoryTransportDispatchHandoff;
import com.xa.mass.transport.runtime.embedded.EmbeddedAdapterRuntimeEnvironment;
import com.xa.mass.transport.runtime.embedded.EmbeddedAdapterRuntimeSpec;
import com.xa.mass.transport.runtime.embedded.EmbeddedTransportAdapterRuntime;
import com.xa.mass.transport.runtime.embedded.EmbeddedTransportAdapterRuntimeFactory;
import com.xa.mass.transport.runtime.lease.CurrentSessionConnectSink;
import com.xa.mass.transport.runtime.lease.CurrentSessionDisconnectSink;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedAdapterStarterTest {

    @Test
    void createBuildsRuntimeRegistryAndStartsByAdapterId() {
        EmbeddedAdapterStarter starter = new EmbeddedAdapterStarter(
                environment(),
                List.of(new StubRuntimeFactory())
        );

        EmbeddedAdapterCreateResult createResult = starter.create(List.of(spec("stub", "adapter-a", "mailbox-a")));
        starter.start("adapter-a");

        assertEquals(List.of("adapter-a"), createResult.adapterIds());
        assertTrue(starter.isRunning());
        assertEquals("adapter-a", starter.resolveRegistrationAdapterId(null, WorkerTransportHints.REALTIME));
        assertEquals("mailbox-a",
                starter.resolveBinding(null, WorkerTransportHints.REALTIME).getAdapterMailboxKey());
        starter.close();
    }

    @Test
    void rejectsUnsupportedAdapterType() {
        EmbeddedAdapterStarter starter = new EmbeddedAdapterStarter(
                environment(),
                List.of(new StubRuntimeFactory())
        );

        assertThrows(IllegalArgumentException.class,
                () -> starter.create(List.of(spec("missing", "adapter-a", "mailbox-a"))));
    }

    @Test
    void rejectsNonDefaultResultQueueKeyForV1() {
        EmbeddedAdapterStarter starter = new EmbeddedAdapterStarter(
                environment(),
                List.of(new StubRuntimeFactory())
        );
        EmbeddedAdapterRuntimeSpec spec = new EmbeddedAdapterRuntimeSpec(
                "stub",
                "adapter-a",
                "mailbox-a",
                "other",
                Map.of()
        );

        assertThrows(IllegalArgumentException.class, () -> starter.create(List.of(spec)));
    }

    private static EmbeddedAdapterRuntimeSpec spec(String type, String adapterId, String mailboxKey) {
        return new EmbeddedAdapterRuntimeSpec(
                type,
                adapterId,
                mailboxKey,
                TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY,
                Map.of()
        );
    }

    private static EmbeddedAdapterRuntimeEnvironment environment() {
        TransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore();
        return new EmbeddedAdapterRuntimeEnvironment(
                new InMemoryTransportDispatchHandoff(10),
                new InMemoryTransportResultIngressQueue(10),
                endpointLeaseStore,
                CurrentSessionConnectSink.NOOP,
                CurrentSessionDisconnectSink.NOOP,
                new DirectExecutor(),
                ignored -> true
        );
    }

    private static final class StubRuntimeFactory implements EmbeddedTransportAdapterRuntimeFactory {

        @Override
        public String type() {
            return "stub";
        }

        @Override
        public TransportAdapterDescriptor descriptor(EmbeddedAdapterRuntimeSpec spec) {
            return new TransportAdapterDescriptor(spec.adapterId(), WorkerTransportHints.REALTIME);
        }

        @Override
        public EmbeddedTransportAdapterRuntime create(EmbeddedAdapterRuntimeSpec spec,
                                                      EmbeddedAdapterRuntimeEnvironment environment) {
            return new StubRuntime(descriptor(spec), TransportBinding.builder(
                            spec.adapterId(),
                            WorkerTransportHints.REALTIME
                    )
                    .adapterMailboxKey(spec.dispatchQueueKey())
                    .protocol("stub")
                    .build());
        }
    }

    private static final class StubRuntime implements EmbeddedTransportAdapterRuntime {
        private final TransportAdapterDescriptor descriptor;
        private final TransportBinding binding;
        private final AtomicBoolean running = new AtomicBoolean();

        private StubRuntime(TransportAdapterDescriptor descriptor, TransportBinding binding) {
            this.descriptor = descriptor;
            this.binding = binding;
        }

        @Override
        public TransportAdapterDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public TransportBinding binding() {
            return binding;
        }

        @Override
        public void start() {
            running.set(true);
        }

        @Override
        public boolean isRunning() {
            return running.get();
        }

        @Override
        public void close() {
            running.set(false);
        }
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
