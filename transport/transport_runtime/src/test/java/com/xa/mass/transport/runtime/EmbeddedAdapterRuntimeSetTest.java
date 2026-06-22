package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.base.runtime.RuntimeTaskExecutorStatistics;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxConsumerLease;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxConsumerRegistry;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedAdapterRuntimeSetTest {

    @Test
    void contributionRuntimeOwnsSharedResourcesAndPerBindingMailboxLeases() {
        List<String> events = new ArrayList<>();
        RecordingMailboxRegistry registry = new RecordingMailboxRegistry(events);
        RecordingRuntimeTaskExecutor executor = new RecordingRuntimeTaskExecutor();
        ManagedTransportAdapter managedAdapter = managedAdapter(events, "managed");
        TransportServer server = server(events, "server");
        TransportBinding bindingOne = binding("websocket", "mailbox-a");
        TransportBinding bindingTwo = binding("socket", "mailbox-b");
        TransportAdapterContribution contribution = TransportAdapterContribution.builder()
                .addManagedTransportAdapter(managedAdapter)
                .addTransportServer(server)
                .addTransportBinding(bindingOne)
                .addTransportBinding(bindingTwo)
                .build();

        EmbeddedAdapterRuntimeSet runtimeSet = EmbeddedAdapterRuntimeSet.fromContributions(
                List.of(contribution),
                registry,
                30_000L,
                executor
        );

        assertEquals(List.of(bindingOne, bindingTwo), runtimeSet.bindings());

        runtimeSet.start();

        assertEquals(List.of(
                "managed-start:managed",
                "server-start:server",
                "claim:mailbox-a",
                "claim:mailbox-b"
        ), events);
        assertEquals(2, executor.submittedTasks);
        assertTrue(runtimeSet.isRunning());

        runtimeSet.stop();

        assertEquals(List.of(
                "managed-start:managed",
                "server-start:server",
                "claim:mailbox-a",
                "claim:mailbox-b",
                "server-stop:server",
                "managed-stop:managed",
                "release:mailbox-a",
                "release:mailbox-b"
        ), events);
    }

    private static TransportBinding binding(String adapterId, String adapterMailboxKey) {
        return TransportBinding.builder(adapterId, WorkerTransportHints.REALTIME, executor())
                .adapterMailboxKey(adapterMailboxKey)
                .protocol(adapterId)
                .build();
    }

    private static AdapterCommandExecutor executor() {
        return commands -> commands == null
                ? List.of()
                : commands.stream().map(DispatchOutcome::delivered).toList();
    }

    private static ManagedTransportAdapter managedAdapter(List<String> events, String name) {
        return new ManagedTransportAdapter() {
            private boolean running;

            @Override
            public void start() {
                running = true;
                events.add("managed-start:" + name);
            }

            @Override
            public void stop() {
                running = false;
                events.add("managed-stop:" + name);
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }

    private static TransportServer server(List<String> events, String name) {
        return new TransportServer() {
            private boolean running;

            @Override
            public void start() {
                running = true;
                events.add("server-start:" + name);
            }

            @Override
            public void stop() {
                running = false;
                events.add("server-stop:" + name);
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }

    private static final class RecordingMailboxRegistry implements AdapterMailboxConsumerRegistry {
        private final List<String> events;

        private RecordingMailboxRegistry(List<String> events) {
            this.events = events;
        }

        @Override
        public void claimMailboxConsumer(AdapterMailboxConsumerLease lease) {
            events.add("claim:" + lease.adapterMailboxKey());
        }

        @Override
        public void releaseMailboxConsumer(AdapterMailboxConsumerLease lease) {
            events.add("release:" + lease.adapterMailboxKey());
        }
    }

    private static final class RecordingRuntimeTaskExecutor implements RuntimeTaskExecutor {
        private int submittedTasks;

        @Override
        public Future<?> submit(Runnable task) {
            submittedTasks++;
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            submittedTasks++;
            return java.util.concurrent.CompletableFuture.completedFuture(null);
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
            return new RuntimeTaskExecutorStatistics(submittedTasks, 0, 0, 0, 0, 1);
        }
    }
}
