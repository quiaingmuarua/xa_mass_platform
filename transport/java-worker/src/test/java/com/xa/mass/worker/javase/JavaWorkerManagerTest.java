package com.xa.mass.worker.javase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.worker.runtime.WorkerIdentityStore;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JavaWorkerManagerTest {

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
                .workerAssembler((platform, key, identity, properties) -> {
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
                        .eventDefinitions(definitions())
                        .build()
        );
        assertThrows(
                IllegalStateException.class,
                () -> baseBuilder()
                        .replica(
                                "client",
                                WorkerIdentityStore.noCache(),
                                Map::of
                        )
                        .build()
        );

        JavaWorkerManager.Builder duplicate = configuredBuilder();
        assertThrows(
                IllegalArgumentException.class,
                () -> duplicate.replica(
                        "client-1",
                        WorkerIdentityStore.noCache(),
                        Map::of
                )
        );
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
                .eventDefinitions(definitions())
                .workerAssembler((platform, key, identity, properties) ->
                        assembled.removeFirst());
        for (int index = 0; index < workers.length; index++) {
            builder.replica(
                    "client-" + (index + 1),
                    WorkerIdentityStore.noCache(),
                    Map::of
            );
        }
        return builder.build();
    }

    private static JavaWorkerManager platformCapturingManager(
            List<JavaWorkerPlatform> platforms
    ) {
        return configuredBuilder()
                .workerAssembler((platform, key, identity, properties) -> {
                    platforms.add(platform);
                    return new FakeWorker(key, new ArrayList<>());
                })
                .build();
    }

    private static JavaWorkerManager.Builder configuredBuilder() {
        return baseBuilder()
                .eventDefinitions(definitions())
                .replica(
                        "client-1",
                        WorkerIdentityStore.noCache(),
                        Map::of
                )
                .replica(
                        "client-2",
                        WorkerIdentityStore.noCache(),
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
        return List.of(WorkerEventDefinition.of(
                "TASK",
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

    private static final class FakeWorker implements WorkerLifecycle {

        private final String name;
        private final List<String> events;
        private final AtomicInteger startCalls = new AtomicInteger();
        private final AtomicInteger stopCalls = new AtomicInteger();
        private final Map<Listener, Boolean> listeners =
                new LinkedHashMap<>();
        private State state = State.STOPPED;
        private boolean failClose;
        private boolean closed;

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
            state = State.RUNNING;
            publish();
        }

        @Override
        public void stop() {
            stopCalls.incrementAndGet();
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
            if (failClose) {
                throw new IllegalStateException("close " + name);
            }
        }

        private void publish() {
            for (Listener listener : listeners.keySet()) {
                listener.onSnapshot(snapshot());
            }
        }
    }
}
