package com.xa.mass.worker.javase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.worker.runtime.WorkerLifecycle;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.Test;

class JavaWorkerManagerTest {

    @Test
    void propertiesReportingIsKeyedAndUsesOnlyTheTargetTransport() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String endpoint = server.url("/worker").toString().replaceFirst("^http", "ws");
            server.enqueue(new MockResponse.Builder().code(200).body(
                    "{\"workerId\":\"worker-1\",\"transportType\":\"WEBSOCKET\","
                            + "\"endpointUri\":\"" + endpoint + "\"}").build());
            var reports = new java.util.concurrent.LinkedBlockingQueue<
                    com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport>();
            var codec = new com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec();
            server.enqueue(new MockResponse.Builder().webSocketUpgrade(new okhttp3.WebSocketListener() {
                @Override
                public void onMessage(okhttp3.WebSocket socket, String message) {
                    reports.add(codec.decodeDeliveryReport(message));
                }
                @Override
                public void onClosing(okhttp3.WebSocket socket, int code, String reason) {
                    socket.close(code, reason);
                }
            }).build());
            try (JavaWorkerManager manager = JavaWorkerManager.builder(
                    URI.create(server.url("/").toString()), "group", WorkerTransportType.WEBSOCKET)
                    .replica("first", () -> Map.of("battery", "87"))
                    .replica("second", () -> Map.of("battery", "99")).build()) {
                assertEquals(false, manager.reportProperties("first"));
                manager.start("first");
                assertTrue(reports.poll(5, TimeUnit.SECONDS) != null); // Identity admitted.
                assertTrue(manager.reportProperties("first"));
                var full = reports.poll(5, TimeUnit.SECONDS);
                assertTrue(full != null);
                assertEquals("worker-1", full.sourceId());
                assertEquals(Map.of("properties", Map.of("battery", "87")),
                        com.xa.mass.workerdelivery.json.Jsons.parseObject(full.payload()));
                assertTrue(manager.reportProperties("first", Map.of("battery", "88"), java.util.Set.of()));
                assertTrue(reports.poll(5, TimeUnit.SECONDS) != null);
                assertEquals(false, manager.reportProperties("second"));
                assertEquals(WorkerLifecycle.State.STOPPED, manager.snapshot("second").state());
                assertEquals(2, server.getRequestCount());
                manager.close();
                assertEquals(false, manager.reportProperties("first"));
            }
        }
    }

    @Test
    void startsStopsAndSnapshotsFixedOrderedReplicas() {
        List<String> events = new ArrayList<>();
        FakeWorker first = new FakeWorker("first", events);
        FakeWorker second = new FakeWorker("second", events);
        JavaWorkerManager manager = manager(first, second);
        try {
            manager.start();

            assertEquals(1, first.startCalls.get());
            assertEquals(1, second.startCalls.get());
            assertEquals(
                    List.of("client-1", "client-2"),
                    new ArrayList<>(manager.snapshots().keySet())
            );

            manager.stop();
            assertEquals(1, first.stopCalls.get());
            assertEquals(1, second.stopCalls.get());
        } finally {
            manager.close();
        }
        assertEquals(List.of("close:second", "close:first"), events);
    }

    @Test
    void endpointStopNeedsAnExplicitReconcile() {
        FakeWorker worker = new FakeWorker("worker", new ArrayList<>());
        JavaWorkerManager manager = manager(worker);
        try {
            manager.start();
            worker.terminate();

            assertEquals(1, worker.startCalls.get());
            assertEquals(WorkerLifecycle.State.STOPPED,
                    manager.snapshot("client-1").state());

            manager.reconcile();
            assertEquals(2, worker.startCalls.get());
        } finally {
            manager.close();
        }
    }

    @Test
    void controlsReplicaDesiredStateIndependently() {
        FakeWorker first = new FakeWorker("first", new ArrayList<>());
        FakeWorker second = new FakeWorker("second", new ArrayList<>());
        JavaWorkerManager manager = manager(first, second);
        try {
            manager.start("client-1");

            assertTrue(manager.desiredRunning("client-1"));
            assertEquals(false, manager.desiredRunning("client-2"));
            assertEquals(1, first.startCalls.get());
            assertEquals(0, second.startCalls.get());

            first.terminate();
            assertEquals(1, first.startCalls.get());

            manager.start("client-1");
            assertEquals(2, first.startCalls.get());

            manager.stop("client-1");
            assertEquals(false, manager.desiredRunning("client-1"));
            assertEquals(1, first.stopCalls.get());
            assertEquals(0, second.stopCalls.get());
        } finally {
            manager.close();
        }
    }

    @Test
    void reconcileTargetsOnlyTheRequestedReplica() {
        FakeWorker first = new FakeWorker("first", new ArrayList<>());
        FakeWorker second = new FakeWorker("second", new ArrayList<>());
        JavaWorkerManager manager = manager(first, second);
        try {
            manager.start();
            first.terminate();
            second.terminate();

            manager.reconcile("client-2");

            assertEquals(1, first.startCalls.get());
            assertEquals(2, second.startCalls.get());
        } finally {
            manager.close();
        }
    }

    @Test
    void repeatedStartDoesNotStartRunningReplicaAgain() {
        FakeWorker worker = new FakeWorker("worker", new ArrayList<>());
        JavaWorkerManager manager = manager(worker);
        try {
            manager.start();
            manager.start();
            manager.reconcile();

            assertEquals(1, worker.startCalls.get());
        } finally {
            manager.close();
        }
    }

    @Test
    void batchPrepareLoadsEachStoppedReplicaOnceAndInjectsCoordinates()
            throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        AtomicInteger firstLoads = new AtomicInteger();
        AtomicInteger secondLoads = new AtomicInteger();
        JavaWorkerManager manager = null;
        try {
            server.enqueue(new MockResponse.Builder()
                    .code(200)
                    .body("["
                            + batchItem(
                            "worker-1",
                            "ws://127.0.0.1:1/one"
                    ) + "," + batchItem(
                            "worker-2",
                            "ws://127.0.0.1:1/two"
                    ) + "]")
                    .build());
            manager = JavaWorkerManager.builder(
                            URI.create(server.url("/").toString()),
                            "group-1",
                            WorkerTransportType.WEBSOCKET
                    )
                    .batchWorkerKind("SCENARIO_LAB")
                    .replica("workers.jsonl:1", () -> {
                        firstLoads.incrementAndGet();
                        return Map.of(
                                "labInventoryKey", "workers.jsonl",
                                "labInventoryLine", "1",
                                "labSlot", "1"
                        );
                    })
                    .replica("workers.jsonl:2", () -> {
                        secondLoads.incrementAndGet();
                        return Map.of(
                                "labInventoryKey", "workers.jsonl",
                                "labInventoryLine", "2",
                                "labSlot", "2"
                        );
                    })
                    .build();

            manager.prepareAndStart(List.of(
                    "workers.jsonl:1",
                    "workers.jsonl:2"
            ));
            awaitWorkerId(manager, "workers.jsonl:1", "worker-1");
            awaitWorkerId(manager, "workers.jsonl:2", "worker-2");

            assertEquals(1, firstLoads.get());
            assertEquals(1, secondLoads.get());
            RecordedRequest request = server.takeRequest(
                    1,
                    TimeUnit.SECONDS
            );
            assertEquals(
                    "/api/v1/worker-groups/group-1/"
                            + "workers:prepare-batch",
                    request.getTarget()
            );

            manager.prepareAndStart(List.of(
                    "workers.jsonl:1",
                    "workers.jsonl:2"
            ));
            assertEquals(1, server.getRequestCount());
            assertEquals(1, firstLoads.get());
            assertEquals(1, secondLoads.get());
        } finally {
            if (manager != null) {
                manager.close();
            }
            server.close();
        }
    }

    @Test
    void stopDuringBatchPreparePreventsTheReturnedCoordinateFromStarting()
            throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        JavaWorkerManager manager = null;
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            server.enqueue(new MockResponse.Builder()
                    .code(200)
                    .body("[" + batchItem(
                            "worker-1",
                            "ws://127.0.0.1:1/one"
                    ) + "]")
                    .bodyDelay(250L, TimeUnit.MILLISECONDS)
                    .build());
            manager = JavaWorkerManager.builder(
                            URI.create(server.url("/").toString()),
                            "group-1",
                            WorkerTransportType.WEBSOCKET
                    )
                    .batchWorkerKind("SCENARIO_LAB")
                    .replica("workers.jsonl:1", () -> Map.of(
                            "labInventoryKey", "workers.jsonl",
                            "labInventoryLine", "1",
                            "labSlot", "1"
                    ))
                    .build();

            JavaWorkerManager controlledManager = manager;
            Future<?> preparing = caller.submit(() ->
                    controlledManager.prepareAndStart(List.of(
                            "workers.jsonl:1"
                    )));
            assertEquals(
                    "/api/v1/worker-groups/group-1/"
                            + "workers:prepare-batch",
                    server.takeRequest(1, TimeUnit.SECONDS).getTarget()
            );

            manager.stop("workers.jsonl:1");
            preparing.get(1, TimeUnit.SECONDS);

            assertEquals(false, manager.desiredRunning("workers.jsonl:1"));
            assertEquals(
                    WorkerLifecycle.State.STOPPED,
                    manager.snapshot("workers.jsonl:1").state()
            );
            assertEquals(
                    null,
                    manager.snapshot("workers.jsonl:1").workerId()
            );
        } finally {
            caller.shutdownNow();
            if (manager != null) {
                manager.close();
            }
            server.close();
        }
    }

    @Test
    void disjointBatchPreparationsDoNotShareAManagerGate()
            throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        JavaWorkerManager manager = null;
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            server.enqueue(new MockResponse.Builder()
                    .code(200)
                    .body("[" + batchItem(
                            "worker-1",
                            "ws://127.0.0.1:1/one"
                    ) + "]")
                    .bodyDelay(500L, TimeUnit.MILLISECONDS)
                    .build());
            server.enqueue(new MockResponse.Builder()
                    .code(200)
                    .body("[" + batchItem(
                            "worker-2",
                            "ws://127.0.0.1:1/two"
                    ) + "]")
                    .build());
            manager = batchManager(server);

            JavaWorkerManager controlledManager = manager;
            Future<?> first = callers.submit(() ->
                    controlledManager.prepareAndStart(List.of(
                            "workers.jsonl:1"
                    )));
            assertTrue(server.takeRequest(1, TimeUnit.SECONDS) != null);

            Future<?> second = callers.submit(() ->
                    controlledManager.prepareAndStart(List.of(
                            "workers.jsonl:2"
                    )));
            assertTrue(server.takeRequest(1, TimeUnit.SECONDS) != null);
            second.get(1, TimeUnit.SECONDS);
            first.get(1, TimeUnit.SECONDS);

            awaitWorkerId(manager, "workers.jsonl:1", "worker-1");
            awaitWorkerId(manager, "workers.jsonl:2", "worker-2");
        } finally {
            callers.shutdownNow();
            if (manager != null) {
                manager.close();
            }
            server.close();
        }
    }

    @Test
    void startRejectsReplicaUntilPriorStopReachesStopped() {
        FakeWorker worker = new FakeWorker("worker", new ArrayList<>());
        JavaWorkerManager manager = manager(worker);
        try {
            manager.start("client-1");
            worker.deferStop = true;
            manager.stop("client-1");

            assertEquals(false, manager.desiredRunning("client-1"));
            assertEquals(WorkerLifecycle.State.RUNNING,
                    manager.snapshot("client-1").state());
            assertThrows(
                    IllegalStateException.class,
                    () -> manager.start("client-1")
            );
            assertEquals(false, manager.desiredRunning("client-1"));
            assertEquals(1, worker.startCalls.get());

            worker.completeStop();
            manager.start("client-1");

            assertTrue(manager.desiredRunning("client-1"));
            assertEquals(2, worker.startCalls.get());
        } finally {
            manager.close();
        }
    }

    @Test
    void groupStartContinuesAfterReplicaConflict() {
        FakeWorker first = new FakeWorker("first", new ArrayList<>());
        FakeWorker second = new FakeWorker("second", new ArrayList<>());
        JavaWorkerManager manager = manager(first, second);
        try {
            manager.start("client-1");
            first.deferStop = true;
            manager.stop("client-1");

            assertThrows(IllegalStateException.class, manager::start);

            assertEquals(false, manager.desiredRunning("client-1"));
            assertTrue(manager.desiredRunning("client-2"));
            assertEquals(1, first.startCalls.get());
            assertEquals(1, second.startCalls.get());
        } finally {
            manager.close();
        }
    }

    @Test
    void keyedOperationsOnDifferentReplicasDoNotShareAGate()
            throws Exception {
        FakeWorker first = new FakeWorker("first", new ArrayList<>());
        FakeWorker second = new FakeWorker("second", new ArrayList<>());
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        first.blockStart(firstEntered, releaseFirst);
        JavaWorkerManager manager = manager(first, second);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<?> blocked = callers.submit(
                    () -> manager.start("client-1")
            );
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));

            Future<?> independent = callers.submit(
                    () -> manager.start("client-2")
            );
            independent.get(1, TimeUnit.SECONDS);

            assertEquals(1, second.startCalls.get());
            releaseFirst.countDown();
            blocked.get(1, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            callers.shutdownNow();
            manager.close();
        }
    }

    @Test
    void explicitReconcileConvergesCurrentConcurrentIntent()
            throws Exception {
        FakeWorker worker = new FakeWorker("worker", new ArrayList<>());
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        worker.blockStart(startEntered, releaseStart);
        JavaWorkerManager manager = manager(worker);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> starting = caller.submit(
                    () -> manager.start("client-1")
            );
            assertTrue(startEntered.await(1, TimeUnit.SECONDS));

            manager.stop("client-1");
            releaseStart.countDown();
            starting.get(1, TimeUnit.SECONDS);
            manager.reconcile("client-1");

            assertEquals(false, manager.desiredRunning("client-1"));
            assertEquals(
                    WorkerLifecycle.State.STOPPED,
                    manager.snapshot("client-1").state()
            );
            assertEquals(1, worker.startCalls.get());
            assertEquals(1, worker.stopCalls.get());
        } finally {
            releaseStart.countDown();
            caller.shutdownNow();
            manager.close();
        }
    }

    @Test
    void groupOperationAggregatesFailuresAndContinuesAllReplicas() {
        FakeWorker first = new FakeWorker("first", new ArrayList<>());
        FakeWorker second = new FakeWorker("second", new ArrayList<>());
        FakeWorker third = new FakeWorker("third", new ArrayList<>());
        first.failStart = true;
        second.failStart = true;
        JavaWorkerManager manager = manager(first, second, third);
        try {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    manager::start
            );

            assertEquals("start first", failure.getMessage());
            assertEquals(1, failure.getSuppressed().length);
            assertEquals(
                    "start second",
                    failure.getSuppressed()[0].getMessage()
            );
            assertEquals(1, third.startCalls.get());
        } finally {
            manager.close();
        }
    }

    @Test
    void closeRejectsNewEntryWhileReverseTeardownContinues()
            throws Exception {
        List<String> events = new ArrayList<>();
        FakeWorker first = new FakeWorker("first", events);
        FakeWorker second = new FakeWorker("second", events);
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        second.blockClose(closeEntered, releaseClose);
        JavaWorkerManager manager = manager(first, second);
        JavaWorkerPlatform platform = managerPlatform(manager);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> closing = caller.submit(manager::close);
            assertTrue(closeEntered.await(1, TimeUnit.SECONDS));

            assertThrows(
                    IllegalStateException.class,
                    () -> manager.start("client-1")
            );
            releaseClose.countDown();
            closing.get(1, TimeUnit.SECONDS);

            assertEquals(
                    List.of("close:second", "close:first"),
                    events
            );
            assertTrue(controlExecutor(platform).isShutdown());
        } finally {
            releaseClose.countDown();
            caller.shutdownNow();
            manager.close();
        }
    }

    @Test
    void replicasShareOneManagerPlatformAndManagersAreIndependent() {
        List<JavaWorkerPlatform> firstPlatforms = new ArrayList<>();
        List<JavaWorkerPlatform> secondPlatforms = new ArrayList<>();
        JavaWorkerManager first = platformCapturingManager(firstPlatforms);
        JavaWorkerManager second = platformCapturingManager(secondPlatforms);
        try {
            assertEquals(2, firstPlatforms.size());
            assertSame(firstPlatforms.get(0), firstPlatforms.get(1));
            assertEquals(2, secondPlatforms.size());
            assertSame(secondPlatforms.get(0), secondPlatforms.get(1));
            assertNotSame(firstPlatforms.get(0), secondPlatforms.get(0));
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    void assemblyFailureClosesReplicasAndManagerPlatform()
            throws Exception {
        FakeWorker first = new FakeWorker("first", new ArrayList<>());
        AtomicInteger calls = new AtomicInteger();
        List<JavaWorkerPlatform> platforms = new ArrayList<>();
        JavaWorkerManager.Builder builder = configuredBuilder()
                .workerAssembler((platform, key, properties) -> {
                    platforms.add(platform);
                    if (calls.getAndIncrement() == 0) {
                        return first;
                    }
                    throw new IllegalStateException("assemble second");
                });

        assertThrows(IllegalStateException.class, builder::build);

        assertTrue(first.closed);
        assertTrue(controlExecutor(platforms.get(0)).isShutdown());
    }

    @Test
    void validatesGroupAndReplicaTopologyBeforeAssembly() {
        assertThrows(
                IllegalArgumentException.class,
                () -> JavaWorkerManager.builder(
                        URI.create("relative"),
                        "group",
                        WorkerTransportType.WEBSOCKET
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> JavaWorkerManager.builder(
                        URI.create("http://127.0.0.1:18082"),
                        "group",
                        WorkerTransportType.POLLING
                )
        );
        assertThrows(
                IllegalStateException.class,
                () -> baseBuilder()
                        .extendEventDefinitions(definitions())
                        .build()
        );

        JavaWorkerManager.Builder duplicate = configuredBuilder();
        assertThrows(
                IllegalArgumentException.class,
                () -> duplicate.replica(
                        "client-1",
                        Map::of
                )
        );
    }

    @Test
    void zeroDefinitionExtensionsCanBuild() {
        JavaWorkerManager manager = baseBuilder()
                .replica(
                        "client",
                        Map::of
                )
                .build();

        manager.close();
    }

    @Test
    void repeatedDefinitionExtensionCallsAccumulate() {
        WorkerEventDefinition<?> definition = definitions().get(0);
        JavaWorkerManager.Builder builder = baseBuilder()
                .extendEventDefinitions(List.of(definition))
                .extendEventDefinitions(List.of(definition))
                .replica(
                        "client",
                        Map::of
                );

        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    void closeAggregatesReplicaFailuresAndIsTerminal() {
        FakeWorker first = new FakeWorker("first", new ArrayList<>());
        FakeWorker second = new FakeWorker("second", new ArrayList<>());
        first.failClose = true;
        second.failClose = true;
        JavaWorkerManager manager = manager(first, second);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                manager::close
        );

        assertEquals("close second", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertThrows(IllegalStateException.class, manager::start);
    }

    private static JavaWorkerManager manager(FakeWorker... workers) {
        Deque<WorkerLifecycle> assembled = new ArrayDeque<>(
                Arrays.asList(workers)
        );
        JavaWorkerManager.Builder builder = baseBuilder()
                .extendEventDefinitions(definitions())
                .workerAssembler((platform, key, properties) ->
                        assembled.removeFirst());
        for (int index = 0; index < workers.length; index++) {
            builder.replica(
                    "client-" + (index + 1),
                    Map::of
            );
        }
        return builder.build();
    }

    private static JavaWorkerManager platformCapturingManager(
            List<JavaWorkerPlatform> platforms
    ) {
        return configuredBuilder()
                .workerAssembler((platform, key, properties) -> {
                    platforms.add(platform);
                    return new FakeWorker(key, new ArrayList<>());
                })
                .build();
    }

    private static JavaWorkerManager.Builder configuredBuilder() {
        return baseBuilder()
                .extendEventDefinitions(definitions())
                .replica(
                        "client-1",
                        Map::of
                )
                .replica(
                        "client-2",
                        Map::of
                );
    }

    private static JavaWorkerManager.Builder baseBuilder() {
        return JavaWorkerManager.builder(
                URI.create("http://127.0.0.1:18082"),
                "group-1",
                WorkerTransportType.WEBSOCKET
        );
    }

    private static List<WorkerEventDefinition<?>> definitions() {
        return List.of(WorkerEventDefinition.extension(
                "test.observe",
                WorkerEventParameterResolvers.jsonMap(),
                parameters -> "null"
        ));
    }

    private static ExecutorService controlExecutor(
            JavaWorkerPlatform platform
    ) throws Exception {
        Field field = JavaWorkerPlatform.class.getDeclaredField(
                "controlExecutor"
        );
        field.setAccessible(true);
        return (ExecutorService) field.get(platform);
    }

    private static JavaWorkerPlatform managerPlatform(
            JavaWorkerManager manager
    ) throws Exception {
        Field field = JavaWorkerManager.class.getDeclaredField("platform");
        field.setAccessible(true);
        return (JavaWorkerPlatform) field.get(manager);
    }

    private static final class FakeWorker implements WorkerLifecycle {

        private final String name;
        private final List<String> events;
        private final AtomicInteger startCalls = new AtomicInteger();
        private final AtomicInteger stopCalls = new AtomicInteger();
        private final Map<Listener, Boolean> listeners =
                new LinkedHashMap<>();
        private volatile State state = State.STOPPED;
        private volatile boolean failStart;
        private volatile boolean failClose;
        private volatile boolean deferStop;
        private volatile boolean closed;
        private volatile CountDownLatch startEntered;
        private volatile CountDownLatch startRelease;
        private volatile CountDownLatch closeEntered;
        private volatile CountDownLatch closeRelease;

        private FakeWorker(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }

        @Override
        public void start() {
            if (closed) {
                throw new IllegalStateException("closed");
            }
            startCalls.incrementAndGet();
            CountDownLatch entered = startEntered;
            CountDownLatch release = startRelease;
            if (entered != null) {
                entered.countDown();
            }
            if (release != null) {
                awaitLatch(release);
            }
            if (failStart) {
                throw new IllegalStateException("start " + name);
            }
            state = State.RUNNING;
            publish();
        }

        private void blockStart(
                CountDownLatch entered,
                CountDownLatch release
        ) {
            startEntered = entered;
            startRelease = release;
        }

        @Override
        public void stop() {
            stopCalls.incrementAndGet();
            if (!deferStop) {
                completeStop();
            }
        }

        private void completeStop() {
            state = State.STOPPED;
            publish();
        }

        private void terminate() {
            state = State.STOPPED;
            publish();
        }

        @Override
        public Snapshot snapshot() {
            return new Snapshot(state, null, null, null);
        }

        @Override
        public void addListener(Listener listener) {
            listeners.put(listener, Boolean.TRUE);
            listener.onSnapshot(snapshot());
        }

        @Override
        public void removeListener(Listener listener) {
            listeners.remove(listener);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            events.add("close:" + name);
            CountDownLatch entered = closeEntered;
            CountDownLatch release = closeRelease;
            if (entered != null) {
                entered.countDown();
            }
            if (release != null) {
                awaitLatch(release);
            }
            if (failClose) {
                throw new IllegalStateException("close " + name);
            }
        }

        private void blockClose(
                CountDownLatch entered,
                CountDownLatch release
        ) {
            closeEntered = entered;
            closeRelease = release;
        }

        private void publish() {
            for (Listener listener : listeners.keySet()) {
                listener.onSnapshot(snapshot());
            }
        }
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

    private static void awaitWorkerId(
            JavaWorkerManager manager,
            String clientWorkerKey,
            String expectedWorkerId
    ) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (expectedWorkerId.equals(
                    manager.snapshot(clientWorkerKey).workerId()
            )) {
                return;
            }
            Thread.sleep(10L);
        }
        assertEquals(
                expectedWorkerId,
                manager.snapshot(clientWorkerKey).workerId()
        );
    }

    private static String batchItem(
            String workerId,
            String endpointUri
    ) {
        return "{\"workerId\":\"" + workerId + "\","
                + "\"transportType\":\"WEBSOCKET\","
                + "\"endpointUri\":\"" + endpointUri + "\"}";
    }

    private static JavaWorkerManager batchManager(MockWebServer server) {
        return JavaWorkerManager.builder(
                        URI.create(server.url("/").toString()),
                        "group-1",
                        WorkerTransportType.WEBSOCKET
                )
                .batchWorkerKind("SCENARIO_LAB")
                .replica("workers.jsonl:1", () -> Map.of(
                        "labInventoryKey", "workers.jsonl",
                        "labInventoryLine", "1",
                        "labSlot", "1"
                ))
                .replica("workers.jsonl:2", () -> Map.of(
                        "labInventoryKey", "workers.jsonl",
                        "labInventoryLine", "2",
                        "labSlot", "2"
                ))
                .build();
    }
}
