package com.xa.mass.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.transport.client.WorkerControlClient;
import com.xa.mass.transport.client.WorkerTransportType;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkerControlPreparationTest {

    private static final PreparedWorker PREPARED = new PreparedWorker(
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1",
            URI.create("ws://127.0.0.1:18083/worker")
    );

    @Test
    void eachPreparationLoadsOneDefensiveCopyAndCallsControlOnce()
            throws Exception {
        FakeControlClient control = new FakeControlClient();
        List<String> tags = new ArrayList<>(List.of("one"));
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("tags", tags);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("clientWorkerKey", "installation-1");
        source.put("nested", nested);
        WorkerControlPreparation preparation = preparation(() -> source, control);

        assertEquals(PREPARED, preparation.prepare());

        assertEquals(1, control.prepareCalls);
        assertThrows(
                UnsupportedOperationException.class,
                () -> control.properties.get(0).put("new", "value")
        );
        source.put("afterPrepare", true);
        tags.add("two");
        assertTrue(!control.properties.get(0).containsKey("afterPrepare"));
        assertEquals(
                List.of("one"),
                ((Map<?, ?>) control.properties.get(0).get("nested"))
                        .get("tags")
        );
    }

    @Test
    void repeatedPreparationReloadsPropertiesAndCallsControlOnceEach()
            throws Exception {
        FakeControlClient control = new FakeControlClient();
        int[] version = {0};
        WorkerControlPreparation preparation = preparation(
                () -> Map.of(
                        "clientWorkerKey", "installation-1",
                        "version", ++version[0]
                ),
                control
        );

        preparation.prepare();
        preparation.prepare();

        assertEquals(2, control.prepareCalls);
        assertEquals(1, control.properties.get(0).get("version"));
        assertEquals(2, control.properties.get(1).get("version"));
    }

    @Test
    void invalidPropertiesFailBeforeControlAndCloseIsOwned() {
        FakeControlClient control = new FakeControlClient();
        WorkerControlPreparation preparation = preparation(Map::of, control);

        assertThrows(IllegalArgumentException.class, preparation::prepare);
        assertEquals(0, control.prepareCalls);
        preparation.close();
        preparation.close();
        assertTrue(control.closed);
        assertThrows(IllegalStateException.class, preparation::prepare);
    }

    @Test
    void closeCancelsBlockedControlWithoutWaitingForPrepare()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger closeCalls = new AtomicInteger();
        WorkerControlClient control = new WorkerControlClient() {
            @Override
            public PreparedWorker prepare(
                    String workerGroupId,
                    WorkerTransportType transportType,
                    Map<String, Object> workerProperties,
                    Duration timeout
            ) {
                entered.countDown();
                awaitLatch(release);
                return PREPARED;
            }

            @Override
            public void close() {
                closeCalls.incrementAndGet();
            }
        };
        WorkerControlPreparation preparation =
                new WorkerControlPreparation(
                        "group-1",
                        WorkerTransportType.WEBSOCKET,
                        () -> Map.of(
                                "clientWorkerKey",
                                "installation-1"
                        ),
                        control,
                        Duration.ofSeconds(1)
                );
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<PreparedWorker> preparing =
                    callers.submit(preparation::prepare);
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            Future<?> closing = callers.submit(preparation::close);
            closing.get(1, TimeUnit.SECONDS);

            assertEquals(1, closeCalls.get());
            assertFalse(preparing.isDone());
            release.countDown();
            assertEquals(PREPARED, preparing.get(1, TimeUnit.SECONDS));
            assertThrows(
                    IllegalStateException.class,
                    preparation::prepare
            );
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    private static WorkerControlPreparation preparation(
            WorkerPropertiesProvider propertiesProvider,
            FakeControlClient control
    ) {
        return new WorkerControlPreparation(
                "group-1",
                WorkerTransportType.WEBSOCKET,
                propertiesProvider,
                control,
                Duration.ofSeconds(1)
        );
    }

    private static void awaitLatch(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException error) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class FakeControlClient
            implements WorkerControlClient {

        private int prepareCalls;
        private boolean closed;
        private final List<Map<String, Object>> properties =
                new ArrayList<>();

        @Override
        public PreparedWorker prepare(
                String workerGroupId,
                WorkerTransportType transportType,
                Map<String, Object> workerProperties,
                Duration timeout
        ) {
            prepareCalls++;
            properties.add(workerProperties);
            return PREPARED;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
