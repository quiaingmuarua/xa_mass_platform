package com.xa.mass.transport.runtime;

import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.WorkerEndpointInspector;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportAdapterContributionTest {

    @Test
    void contributionKeepsAppendOnlyOutputsByRole() {
        TransportBinding bindingOne = binding("ws-public");
        TransportBinding bindingTwo = binding("ws-internal");
        ManagedTransportAdapter managedAdapter = managedAdapter();
        TransportServer server = server();
        RawWorkerMessageChannel rawChannel = rawChannel("ws-public");
        WorkerEndpointInspector inspector = List::of;

        TransportAdapterContribution contribution = TransportAdapterContribution.builder()
                .addTransportBinding(bindingOne)
                .addTransportBinding(bindingTwo)
                .addManagedTransportAdapter(managedAdapter)
                .addTransportServer(server)
                .addRawWorkerMessageChannel(rawChannel)
                .addEndpointInspector(inspector)
                .build();

        assertEquals(List.of(bindingOne, bindingTwo), contribution.getTransportBindings());
        assertEquals(List.of(managedAdapter), contribution.getManagedTransportAdapters());
        assertEquals(List.of(server), contribution.getTransportServers());
        assertEquals(List.of(rawChannel), contribution.getRawWorkerMessageChannels());
        assertEquals(List.of(inspector), contribution.getEndpointInspectors());
    }

    @Test
    void contributionOutputsAreImmutable() {
        TransportAdapterContribution contribution = TransportAdapterContribution.builder()
                .addTransportBinding(binding("websocket"))
                .build();

        assertThrows(UnsupportedOperationException.class,
                () -> contribution.getTransportBindings().add(binding("socket")));
    }

    @Test
    void descriptorMustMatchEveryContributedBinding() {
        TransportAdapterContribution contribution = TransportAdapterContribution.builder()
                .addTransportBinding(binding("socket"))
                .build();

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> contribution.validateAgainst(new TransportAdapterDescriptor(
                        "websocket",
                        WorkerTransportHints.REALTIME
                ))
        );

        assertEquals("Transport adapter descriptor adapterId 'websocket' does not match contributed binding adapterId 'socket'",
                error.getMessage());
    }

    @Test
    void descriptorMustMatchContributedTransportHint() {
        TransportAdapterContribution contribution = TransportAdapterContribution.builder()
                .addTransportBinding(TransportBinding.builder(
                        "websocket",
                        WorkerTransportHints.POLLING,
                        executor()
                )
                        .adapterMailboxKey("websocket")
                        .build())
                .build();

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> contribution.validateAgainst(new TransportAdapterDescriptor(
                        "websocket",
                        WorkerTransportHints.REALTIME
                ))
        );

        assertEquals("Transport adapter descriptor transportHint 'realtime' does not match contributed binding transportHint 'polling' for adapterId 'websocket'",
                error.getMessage());
    }

    @Test
    void matchingDescriptorPasses() {
        TransportAdapterContribution contribution = TransportAdapterContribution.builder()
                .addTransportBinding(binding("websocket"))
                .build();

        contribution.validateAgainst(new TransportAdapterDescriptor("websocket", WorkerTransportHints.REALTIME));
    }

    private static TransportBinding binding(String adapterId) {
        return TransportBinding.builder(adapterId, WorkerTransportHints.REALTIME, executor())
                .adapterMailboxKey(adapterId)
                .protocol(adapterId)
                .build();
    }

    private static AdapterCommandExecutor executor() {
        return commands -> commands == null
                ? List.of()
                : commands.stream().map(DispatchOutcome::delivered).toList();
    }

    private static ManagedTransportAdapter managedAdapter() {
        return new ManagedTransportAdapter() {
            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }

            @Override
            public boolean isRunning() {
                return false;
            }
        };
    }

    private static TransportServer server() {
        return new TransportServer() {
            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }

            @Override
            public boolean isRunning() {
                return false;
            }
        };
    }

    private static RawWorkerMessageChannel rawChannel(String adapterId) {
        return new RawWorkerMessageChannel() {
            @Override
            public String adapterId() {
                return adapterId;
            }

            @Override
            public void sendToAdapterRoute(String routeKey, String rawJson, String traceId) {
            }
        };
    }
}
