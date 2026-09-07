package com.xa.mass.worker.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.*;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.worker.execution.WorkerCommandDispatcher;
import com.xa.mass.worker.execution.WorkerManagementEventDefinitions;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WorkerPropertiesReportingTest {
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    @Test
    void activeReportingReadsOneProviderAndDoesNotPrepareOrStorePatches() {
        AtomicReference<Map<String, String>> host = new AtomicReference<>(Map.of("battery", "87"));
        AtomicInteger loads = new AtomicInteger();
        Fixture f = new Fixture(() -> { loads.incrementAndGet(); return host.get(); });
        try (WorkerRunController worker = f.worker) {
            assertFalse(worker.reportProperties());
            assertFalse(worker.reportProperties(Map.of(), Set.of()));
            assertEquals(0, loads.get());
            worker.start();
            assertTrue(worker.reportProperties());
            assertFull(f.last(), Map.of("battery", "87"));
            host.set(Map.of("battery", "88", "network.type", "wifi"));
            assertTrue(worker.reportProperties(Map.of("battery", "88"), Set.of("old")));
            assertEquals(Map.of("set", Map.of("battery", "88"), "remove", List.of("old")),
                    Jsons.parseObject(f.last().payload()));
            assertEquals(1, loads.get());
            assertTrue(worker.reportProperties());
            assertFull(f.last(), host.get());
            assertEquals(2, loads.get());
            assertEquals(1, f.prepares.get());
            f.client.accept = false;
            assertFalse(worker.reportProperties());
            f.client.accept = true;
            worker.stop();
            assertFalse(worker.reportProperties());
            assertEquals(WorkerLifecycle.State.STOPPED, worker.snapshot().state());
        }
        assertFalse(f.worker.reportProperties());
    }

    @Test
    void adapterSnapshotUsesReportedOnlyWhileTaskAndSystemKeepCorrelation() {
        Fixture f = new Fixture(() -> Map.of("network.type", "wifi"));
        try (WorkerRunController worker = f.worker) {
            worker.start();
            f.client.sent.clear();
            for (var source : List.of(ADAPTER, TASK, SYSTEM)) {
                DeliveryCommand command = DeliveryCommand.create(source, WORKER,
                        "platform.worker.properties.snapshot", System.currentTimeMillis() + 60_000,
                        "null", source == ADAPTER ? "" : "opaque-correlation");
                f.client.listener.onMessage(codec.encodeDeliveryCommand(command));
                DeliveryReport report = f.last();
                if (source == ADAPTER) {
                    assertFull(report, Map.of("network.type", "wifi"));
                } else {
                    assertEquals(source, report.dst());
                    assertEquals(command.messageType(), report.messageType());
                    assertEquals(command.forward(), report.forward());
                }
            }
            assertEquals(3, f.client.sent.size());
        }
    }

    @Test
    void invalidPatchFailsImmediatelyAndProviderOrEncodedSizeFailureDoesNotEndRun() {
        AtomicReference<Map<String, String>> host = new AtomicReference<>(Map.of());
        Fixture f = new Fixture(() -> {
            if (host.get() == null) throw new Exception("opaque private provider failure");
            return host.get();
        });
        try (WorkerRunController worker = f.worker) {
            assertThrows(IllegalArgumentException.class,
                    () -> worker.reportProperties(Map.of(" ", "x"), Set.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> worker.reportProperties(Map.of("a", "x"), Set.of("a")));
            assertThrows(IllegalArgumentException.class,
                    () -> worker.reportProperties(Map.of(), Set.of(" ")));
            worker.start();
            f.client.sent.clear();
            host.set(null);
            assertFalse(worker.reportProperties());
            host.set(Map.of("value", "中".repeat(340_000)));
            assertFalse(worker.reportProperties());
            assertFalse(worker.reportProperties(host.get(), Set.of()));
            // JSON escaping can exceed the frame limit even when the raw value does not.
            host.set(Map.of("value", "\"".repeat(300_000)));
            assertFalse(worker.reportProperties());
            assertTrue(f.client.sent.isEmpty());
            assertEquals(WorkerLifecycle.State.RUNNING, worker.snapshot().state());
            host.set(Map.of("empty", ""));
            assertTrue(worker.reportProperties());
            assertFull(f.last(), host.get());
        }
    }

    @Test
    void stopDoesNotHoldStateGateAcrossProviderAndLateSendIsRejected() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Fixture f = new Fixture(() -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return Map.of("battery", "87");
        });
        var executor = Executors.newSingleThreadExecutor();
        try (WorkerRunController worker = f.worker) {
            worker.start();
            var sending = executor.submit(() -> worker.reportProperties());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            worker.stop();
            assertTrue(f.client.closed);
            assertEquals(WorkerLifecycle.State.STOPPED, worker.snapshot().state());
            release.countDown();
            assertFalse(sending.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private void assertFull(DeliveryReport report, Map<String, String> properties) {
        assertEquals(WORKER, report.src());
        assertEquals("worker-1", report.sourceId());
        assertEquals(ADAPTER, report.dst());
        assertEquals("200", report.outcomeCode());
        assertEquals("", report.forward());
        assertEquals("platform.worker.properties.reported", report.messageType());
        assertEquals(Map.of("properties", properties), Jsons.parseObject(report.payload()));
    }

    private final class Fixture {
        final Client client = new Client();
        final AtomicInteger prepares = new AtomicInteger();
        final WorkerRunController worker;

        Fixture(WorkerPropertiesProvider provider) {
            worker = new WorkerRunController(new WorkerPreparation() {
                public PreparedWorker prepare() {
                    prepares.incrementAndGet();
                    return new PreparedWorker("worker-1", URI.create("ws://127.0.0.1/worker"));
                }
                public void close() { }
            }, new TextMessageWorkerTransportFactory(uri -> client,
                    WorkerCommandDispatcher.forWorker(WorkerManagementEventDefinitions.assemble(provider, List.of())),
                    provider), Runnable::run);
        }

        DeliveryReport last() {
            return codec.decodeDeliveryReport(client.sent.get(client.sent.size() - 1));
        }
    }

    private static final class Client implements TextMessageClient {
        final List<String> sent = new ArrayList<>();
        volatile boolean closed;
        boolean accept = true;
        Listener listener;
        public void start(Listener listener) { this.listener = listener; listener.onOpen(); }
        public boolean send(String message) {
            if (closed || !accept) return false;
            sent.add(message);
            return true;
        }
        public void closeCurrent(CloseReason reason) { }
        public void close() { closed = true; }
    }
}
