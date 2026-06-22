package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.VirtualThreadRuntimeTaskExecutor;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxConsumerLease;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxConsumerRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdapterMailboxLeaseRuntimeTest {

    @Test
    void mailboxLeaseRuntimeClaimsRefreshesAndReleasesCurrentLease() throws Exception {
        RecordingMailboxRegistry registry = new RecordingMailboxRegistry(2);
        VirtualThreadRuntimeTaskExecutor executor =
                new VirtualThreadRuntimeTaskExecutor("mailbox-lease-test-", 10);
        AdapterMailboxLeaseRuntime runtime = new AdapterMailboxLeaseRuntime(
                TransportBinding.builder("websocket", WorkerTransportHints.REALTIME, commands -> List.of())
                        .adapterMailboxKey("mailbox-a")
                        .build(),
                registry,
                300L,
                executor
        );

        try {
            runtime.start();

            assertTrue(registry.awaitClaims(2, TimeUnit.SECONDS), "lease should be refreshed at least once");
            runtime.stop();

            assertTrue(registry.claims().size() >= 2);
            assertNotNull(registry.released());
            assertEquals("mailbox-a", registry.released().adapterMailboxKey());
            assertEquals(registry.claims().getLast(), registry.released());
        } finally {
            runtime.stop();
            executor.shutdown();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private static final class RecordingMailboxRegistry implements AdapterMailboxConsumerRegistry {
        private final List<AdapterMailboxConsumerLease> claims = new CopyOnWriteArrayList<>();
        private final CountDownLatch claimLatch;
        private volatile AdapterMailboxConsumerLease released;

        private RecordingMailboxRegistry(int expectedClaims) {
            this.claimLatch = new CountDownLatch(expectedClaims);
        }

        @Override
        public void claimMailboxConsumer(AdapterMailboxConsumerLease lease) {
            claims.add(lease);
            claimLatch.countDown();
        }

        @Override
        public void releaseMailboxConsumer(AdapterMailboxConsumerLease lease) {
            released = lease;
        }

        private boolean awaitClaims(long timeout, TimeUnit unit) throws InterruptedException {
            return claimLatch.await(timeout, unit);
        }

        private List<AdapterMailboxConsumerLease> claims() {
            return claims;
        }

        private AdapterMailboxConsumerLease released() {
            return released;
        }
    }
}
