package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.VirtualThreadRuntimeTaskExecutor;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxConsumerAvailability;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxConsumerRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailboxConsumerAvailabilityPublisherTest {

    @Test
    void mailboxAvailabilityPublisherPublishesRefreshesAndRemovesCurrentAvailability() throws Exception {
        RecordingMailboxRegistry registry = new RecordingMailboxRegistry(2);
        VirtualThreadRuntimeTaskExecutor executor =
                new VirtualThreadRuntimeTaskExecutor("mailbox-availability-test-", 10);
        MailboxConsumerAvailabilityPublisher publisher = new MailboxConsumerAvailabilityPublisher(
                TransportBinding.builder("websocket", WorkerTransportHints.REALTIME, commands -> List.of())
                        .adapterMailboxKey("mailbox-a")
                        .build(),
                registry,
                300L,
                executor
        );

        try {
            publisher.start();

            assertTrue(registry.awaitClaims(2, TimeUnit.SECONDS), "availability should be refreshed at least once");
            publisher.stop();

            assertTrue(registry.claims().size() >= 2);
            assertNotNull(registry.released());
            assertEquals("mailbox-a", registry.released().adapterMailboxKey());
            assertEquals(registry.claims().getLast(), registry.released());
        } finally {
            publisher.stop();
            executor.shutdown();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private static final class RecordingMailboxRegistry implements AdapterMailboxConsumerRegistry {
        private final List<AdapterMailboxConsumerAvailability> claims = new CopyOnWriteArrayList<>();
        private final CountDownLatch claimLatch;
        private volatile AdapterMailboxConsumerAvailability released;

        private RecordingMailboxRegistry(int expectedClaims) {
            this.claimLatch = new CountDownLatch(expectedClaims);
        }

        @Override
        public void publishMailboxConsumerAvailability(AdapterMailboxConsumerAvailability availability) {
            claims.add(availability);
            claimLatch.countDown();
        }

        @Override
        public void removeMailboxConsumerAvailability(AdapterMailboxConsumerAvailability availability) {
            released = availability;
        }

        private boolean awaitClaims(long timeout, TimeUnit unit) throws InterruptedException {
            return claimLatch.await(timeout, unit);
        }

        private List<AdapterMailboxConsumerAvailability> claims() {
            return claims;
        }

        private AdapterMailboxConsumerAvailability released() {
            return released;
        }
    }
}
