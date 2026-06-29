package com.xa.mass.transport.polling.runtime;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.base.runtime.RuntimeTaskExecutorStatistics;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.runtime.InMemoryTransportResultIngressQueue;
import com.xa.mass.transport.runtime.TransportResultIngressQueue;
import com.xa.mass.transport.runtime.delivery.InMemoryTransportDispatchHandoff;
import com.xa.mass.transport.runtime.embedded.EmbeddedAdapterRuntimeEnvironment;
import com.xa.mass.transport.runtime.embedded.EmbeddedAdapterRuntimeSpec;
import com.xa.mass.transport.runtime.embedded.EmbeddedTransportAdapterRuntime;
import com.xa.mass.transport.runtime.lease.CurrentSessionDisconnectSink;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PollingAdapterRuntimeFactoryTest {

    @Test
    void descriptorAndRuntimeUseSpecAdapterIdAndDispatchQueueKey() {
        PollingAdapterRuntimeFactory factory = new PollingAdapterRuntimeFactory();
        EmbeddedAdapterRuntimeSpec spec = new EmbeddedAdapterRuntimeSpec(
                PollingAdapterRuntimeFactory.TYPE,
                "polling-edge",
                "polling-mailbox",
                TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY,
                Map.of()
        );

        assertEquals("polling-edge", factory.descriptor(spec).getAdapterId());
        assertEquals(WorkerTransportHints.POLLING, factory.descriptor(spec).getTransportHint());

        EmbeddedTransportAdapterRuntime runtime = factory.create(spec, environment());

        assertEquals("polling-edge", runtime.descriptor().getAdapterId());
        assertEquals("polling-mailbox", runtime.binding().getAdapterMailboxKey());
        assertEquals(PollingAdapterRuntimeFactory.PROTOCOL, runtime.binding().getProtocol());
        assertNotNull(runtime.binding().getDeliveryPullChannel());
        assertNotNull(runtime.binding().getPullSessionEvidenceDriver());
    }

    private static EmbeddedAdapterRuntimeEnvironment environment() {
        return new EmbeddedAdapterRuntimeEnvironment(
                new InMemoryTransportDispatchHandoff(10),
                new InMemoryTransportResultIngressQueue(10),
                new InMemoryTransportEndpointLeaseStore(),
                CurrentSessionDisconnectSink.NOOP,
                new DirectExecutor()
        );
    }

    private static final class DirectExecutor implements RuntimeTaskExecutor {
        @Override
        public Future<?> submit(Runnable task) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            return CompletableFuture.completedFuture(null);
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
