package com.xa.mass.transport.runtime;

import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.runtime.embedded.AdapterMailboxConsumer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedAdapterHostSetTest {

    @Test
    void contributionHostStartsAdapterOwnedMailboxConsumersWithSharedResources() {
        List<String> events = new ArrayList<>();
        ManagedTransportAdapter managedAdapter = managedAdapter(events, "managed");
        TransportServer server = server(events, "server");
        TransportBinding bindingOne = binding("websocket", "mailbox-a");
        TransportBinding bindingTwo = binding("socket", "mailbox-b");
        AdapterMailboxConsumer consumerOne = mailboxConsumer(events, "mailbox-a");
        AdapterMailboxConsumer consumerTwo = mailboxConsumer(events, "mailbox-b");
        TransportAdapterContribution contribution = TransportAdapterContribution.builder()
                .addManagedTransportAdapter(managedAdapter)
                .addTransportServer(server)
                .addTransportBinding(bindingOne)
                .addTransportBinding(bindingTwo)
                .addAdapterMailboxConsumer(consumerOne)
                .addAdapterMailboxConsumer(consumerTwo)
                .build();

        EmbeddedAdapterHostSet hostSet = EmbeddedAdapterHostSet.fromContributions(List.of(contribution));

        assertEquals(List.of(bindingOne, bindingTwo), hostSet.bindings());

        hostSet.start();

        assertEquals(List.of(
                "managed-start:managed",
                "server-start:server",
                "consumer-start:mailbox-a",
                "consumer-start:mailbox-b"
        ), events);
        assertTrue(hostSet.isRunning());

        hostSet.stop();

        assertEquals(List.of(
                "managed-start:managed",
                "server-start:server",
                "consumer-start:mailbox-a",
                "consumer-start:mailbox-b",
                "consumer-stop:mailbox-a",
                "consumer-stop:mailbox-b",
                "server-stop:server",
                "managed-stop:managed"
        ), events);
        assertFalse(hostSet.isRunning());
    }

    private static TransportBinding binding(String adapterId, String adapterMailboxKey) {
        return TransportBinding.builder(adapterId, WorkerTransportHints.REALTIME)
                .adapterMailboxKey(adapterMailboxKey)
                .protocol(adapterId)
                .build();
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

    private static AdapterMailboxConsumer mailboxConsumer(List<String> events, String mailboxKey) {
        return new AdapterMailboxConsumer() {
            private boolean running;

            @Override
            public String adapterMailboxKey() {
                return mailboxKey;
            }

            @Override
            public void start() {
                running = true;
                events.add("consumer-start:" + mailboxKey);
            }

            @Override
            public void stop() {
                running = false;
                events.add("consumer-stop:" + mailboxKey);
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }
}
