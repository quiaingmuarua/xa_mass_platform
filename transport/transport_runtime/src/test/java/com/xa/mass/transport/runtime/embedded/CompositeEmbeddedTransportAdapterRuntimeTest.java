package com.xa.mass.transport.runtime.embedded;

import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.runtime.ManagedTransportAdapter;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportBinding;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompositeEmbeddedTransportAdapterRuntimeTest {

    @Test
    void closeStopsServersBeforeManagedAdapters() {
        List<String> order = new ArrayList<>();
        CompositeEmbeddedTransportAdapterRuntime runtime = runtime(
                List.of(new RecordingManagedTransportAdapter(order, "adapter")),
                List.of(new RecordingTransportServer(order, "server", false))
        );

        runtime.start();
        order.clear();
        runtime.close();

        assertEquals(List.of("server-stop", "adapter-stop"), order);
    }

    @Test
    void startFailureRollsBackStartedResources() {
        List<String> order = new ArrayList<>();
        CompositeEmbeddedTransportAdapterRuntime runtime = runtime(
                List.of(new RecordingManagedTransportAdapter(order, "adapter")),
                List.of(new RecordingTransportServer(order, "server", true))
        );

        assertThrows(IllegalStateException.class, runtime::start);

        assertEquals(List.of("adapter-start", "server-start", "server-stop", "adapter-stop"), order);
    }

    private CompositeEmbeddedTransportAdapterRuntime runtime(List<ManagedTransportAdapter> adapters,
                                                            List<TransportServer> servers) {
        TransportAdapterDescriptor descriptor = new TransportAdapterDescriptor("adapter", WorkerTransportHints.REALTIME);
        TransportBinding binding = TransportBinding.builder("adapter", WorkerTransportHints.REALTIME)
                .adapterMailboxKey("adapter")
                .protocol("test")
                .build();
        return new CompositeEmbeddedTransportAdapterRuntime(descriptor, binding, adapters, servers);
    }

    private static final class RecordingManagedTransportAdapter implements ManagedTransportAdapter {
        private final List<String> order;
        private final String name;

        private RecordingManagedTransportAdapter(List<String> order, String name) {
            this.order = order;
            this.name = name;
        }

        @Override
        public void start() {
            order.add(name + "-start");
        }

        @Override
        public void stop() {
            order.add(name + "-stop");
        }

        @Override
        public boolean isRunning() {
            return true;
        }
    }

    private static final class RecordingTransportServer implements TransportServer {
        private final List<String> order;
        private final String name;
        private final boolean failStart;

        private RecordingTransportServer(List<String> order, String name, boolean failStart) {
            this.order = order;
            this.name = name;
            this.failStart = failStart;
        }

        @Override
        public void start() {
            order.add(name + "-start");
            if (failStart) {
                throw new IllegalStateException(name + " failed");
            }
        }

        @Override
        public void stop() {
            order.add(name + "-stop");
        }

        @Override
        public boolean isRunning() {
            return true;
        }
    }
}
