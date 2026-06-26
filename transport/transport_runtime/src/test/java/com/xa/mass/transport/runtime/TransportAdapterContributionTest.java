package com.xa.mass.transport.runtime;

import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.runtime.embedded.AdapterMailboxConsumer;
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
        AdapterMailboxConsumer mailboxConsumer = mailboxConsumer("mailbox-a");

        TransportAdapterContribution contribution = TransportAdapterContribution.builder()
                .addTransportBinding(bindingOne)
                .addTransportBinding(bindingTwo)
                .addManagedTransportAdapter(managedAdapter)
                .addAdapterMailboxConsumer(mailboxConsumer)
                .addTransportServer(server)
                .build();

        assertEquals(List.of(bindingOne, bindingTwo), contribution.getTransportBindings());
        assertEquals(List.of(managedAdapter), contribution.getManagedTransportAdapters());
        assertEquals(List.of(mailboxConsumer), contribution.getAdapterMailboxConsumers());
        assertEquals(List.of(server), contribution.getTransportServers());
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
                ), "websocket")
        );

        assertEquals("Transport adapter descriptor adapterId 'websocket' does not match contributed binding adapterId 'socket'",
                error.getMessage());
    }

    @Test
    void descriptorMustMatchContributedTransportHint() {
        TransportAdapterContribution contribution = TransportAdapterContribution.builder()
                .addTransportBinding(TransportBinding.builder(
                        "websocket",
                        WorkerTransportHints.POLLING
                )
                        .adapterMailboxKey("websocket")
                        .build())
                .build();

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> contribution.validateAgainst(new TransportAdapterDescriptor(
                        "websocket",
                        WorkerTransportHints.REALTIME
                ), "websocket")
        );

        assertEquals("Transport adapter descriptor transportHint 'realtime' does not match contributed binding transportHint 'polling' for adapterId 'websocket'",
                error.getMessage());
    }

    @Test
    void matchingDescriptorPasses() {
        TransportAdapterContribution contribution = TransportAdapterContribution.builder()
                .addTransportBinding(binding("websocket"))
                .build();

        contribution.validateAgainst(new TransportAdapterDescriptor("websocket", WorkerTransportHints.REALTIME),
                "websocket");
    }

    @Test
    void assignedMailboxMustMatchContributedBindingMailbox() {
        TransportAdapterContribution contribution = TransportAdapterContribution.builder()
                .addTransportBinding(binding("websocket"))
                .build();

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> contribution.validateAgainst(new TransportAdapterDescriptor(
                        "websocket",
                        WorkerTransportHints.REALTIME
                ), "mailbox-b")
        );

        assertEquals("Transport adapter assigned mailbox key 'mailbox-b' does not match contributed binding mailbox key 'websocket' for adapterId 'websocket'",
                error.getMessage());
    }

    @Test
    void assignedMailboxMustMatchContributedMailboxConsumer() {
        TransportAdapterContribution contribution = TransportAdapterContribution.builder()
                .addAdapterMailboxConsumer(mailboxConsumer("mailbox-a"))
                .build();

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> contribution.validateAgainst(new TransportAdapterDescriptor(
                        "websocket",
                        WorkerTransportHints.REALTIME
                ), "mailbox-b")
        );

        assertEquals("Transport adapter assigned mailbox key 'mailbox-b' does not match contributed mailbox consumer key 'mailbox-a'",
                error.getMessage());
    }

    private static TransportBinding binding(String adapterId) {
        return TransportBinding.builder(adapterId, WorkerTransportHints.REALTIME)
                .adapterMailboxKey(adapterId)
                .protocol(adapterId)
                .build();
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

    private static AdapterMailboxConsumer mailboxConsumer(String mailboxKey) {
        return new AdapterMailboxConsumer() {
            @Override
            public String adapterMailboxKey() {
                return mailboxKey;
            }

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

}
