package com.xa.mass.worker.javase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.worker.runtime.WorkerExecutionResources;
import com.xa.mass.worker.runtime.WorkerIdentityStore;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class JavaWorkerManagerTest {

    @Test
    void buildsOneFixedOrderedGroupAndStartsEveryReplica() {
        List<String> events = new ArrayList<>();
        FakeWorker first = new FakeWorker("first", events);
        FakeWorker second = new FakeWorker("second", events);
        try (TestResources resources = new TestResources()) {
            JavaWorkerManager manager = managerBuilder(
                    resources.resources,
                    first,
                    second
            )
                    .replica(
                            "client-1",
                            WorkerIdentityStore.noCache(),
                            Collections::emptyMap
                    )
                    .replica(
                            "client-2",
                            WorkerIdentityStore.noCache(),
                            () -> Map.of("region", "local")
                    )
                    .build();

            assertEquals(
                    List.of("client-1", "client-2"),
                    new ArrayList<>(manager.snapshots().keySet())
            );
            assertEquals(
                    WorkerLifecycle.State.STOPPED,
                    manager.snapshot("client-1").state()
            );

            manager.start();

            assertEquals(
                    List.of("start:first", "start:second"),
                    events
            );
            assertEquals(
                    WorkerLifecycle.State.RUNNING,
                    manager.snapshot("client-2").state()
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.snapshot("missing")
            );

            manager.close();
            assertEquals(
                    List.of(
                            "start:first",
                            "start:second",
                            "close:second",
                            "close:first"
                    ),
                    events
            );
            assertFalse(resources.control.isShutdown());
        }
    }

    @Test
    void validatesGroupAndReplicaTopologyBeforeAssembly() {
        try (TestResources resources = new TestResources()) {
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
                            " ",
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

            JavaWorkerManager.Builder empty = baseBuilder()
                    .executionResources(resources.resources)
                    .eventDefinitions(definitions());
            assertThrows(IllegalStateException.class, empty::build);

            JavaWorkerManager.Builder duplicate = baseBuilder()
                    .executionResources(resources.resources)
                    .eventDefinitions(definitions())
                    .replica(
                            "client",
                            WorkerIdentityStore.noCache(),
                            Collections::emptyMap
                    );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> duplicate.replica(
                            "client",
                            WorkerIdentityStore.noCache(),
                            Collections::emptyMap
                    )
            );

            assertThrows(
                    IllegalStateException.class,
                    () -> baseBuilder()
                            .eventDefinitions(definitions())
                            .replica(
                                    "client",
                                    WorkerIdentityStore.noCache(),
                                    Collections::emptyMap
                            )
                            .build()
            );
            assertThrows(
                    IllegalStateException.class,
                    () -> baseBuilder()
                            .executionResources(resources.resources)
                            .replica(
                                    "client",
                                    WorkerIdentityStore.noCache(),
                                    Collections::emptyMap
                            )
                            .build()
            );
        }
    }

    @Test
    void reconcilesStoppedReplicasOnlyWhenExplicitlyInvoked() {
        FakeWorker first = new FakeWorker("first", new ArrayList<>());
        FakeWorker second = new FakeWorker("second", new ArrayList<>());
        try (TestResources resources = new TestResources()) {
            JavaWorkerManager manager = manager(
                    resources.resources,
                    first,
                    second
            );

            manager.start();
            first.terminate();

            assertEquals(1, first.startCalls);
            assertEquals(1, second.startCalls);
            assertEquals(
                    WorkerLifecycle.State.STOPPED,
                    manager.snapshot("client-1").state()
            );

            manager.reconcile();

            assertEquals(2, first.startCalls);
            assertEquals(1, second.startCalls);
            manager.close();
        }
    }

    @Test
    void startDuringGracefulStopWaitsForLaterReconcile() {
        FakeWorker worker = new FakeWorker("worker", new ArrayList<>());
        worker.completeStopImmediately = false;
        try (TestResources resources = new TestResources()) {
            JavaWorkerManager manager = manager(
                    resources.resources,
                    worker
            );

            manager.start();
            manager.stop();
            manager.start();

            assertEquals(1, worker.startCalls);
            assertEquals(WorkerLifecycle.State.RUNNING, worker.state);

            worker.finishStop();
            assertEquals(1, worker.startCalls);

            manager.reconcile();
            assertEquals(2, worker.startCalls);
            manager.close();
        }
    }

    @Test
    void isolatesSynchronousFailuresAndRetriesTheGroupLater() {
        FakeWorker first = new FakeWorker("first", new ArrayList<>());
        FakeWorker second = new FakeWorker("second", new ArrayList<>());
        FakeWorker third = new FakeWorker("third", new ArrayList<>());
        first.startFailuresRemaining = 1;
        second.startFailuresRemaining = 1;
        try (TestResources resources = new TestResources()) {
            JavaWorkerManager manager = manager(
                    resources.resources,
                    first,
                    second,
                    third
            );

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    manager::start
            );

            assertTrue(failure.getMessage().contains("first"));
            assertEquals(1, failure.getSuppressed().length);
            assertEquals(1, first.startCalls);
            assertEquals(1, second.startCalls);
            assertEquals(1, third.startCalls);

            manager.reconcile();

            assertEquals(2, first.startCalls);
            assertEquals(2, second.startCalls);
            assertEquals(1, third.startCalls);
            manager.close();
        }
    }

    @Test
    void assemblyFailureClosesCompletedReplicasWithoutClosingResources() {
        List<String> events = new ArrayList<>();
        FakeWorker first = new FakeWorker("first", events);
        try (TestResources resources = new TestResources()) {
            Deque<WorkerLifecycle> assembled = new ArrayDeque<>();
            assembled.add(first);
            JavaWorkerManager.Builder builder = configuredBuilder(
                    resources.resources
            ).workerAssembler(ignored -> {
                if (assembled.isEmpty()) {
                    throw new IllegalStateException("assemble second");
                }
                return assembled.removeFirst();
            });
            builder.replica(
                    "first",
                    WorkerIdentityStore.noCache(),
                    Collections::emptyMap
            );
            builder.replica(
                    "second",
                    WorkerIdentityStore.noCache(),
                    Collections::emptyMap
            );

            assertThrows(IllegalStateException.class, builder::build);

            assertEquals(List.of("close:first"), events);
            assertFalse(resources.control.isShutdown());
        }
    }

    @Test
    void closeAggregatesReplicaFailuresAndNeverClosesSharedResources() {
        List<String> events = new ArrayList<>();
        FakeWorker first = new FakeWorker("first", events);
        FakeWorker second = new FakeWorker("second", events);
        first.failClose = true;
        second.failClose = true;
        try (TestResources resources = new TestResources()) {
            JavaWorkerManager manager = manager(
                    resources.resources,
                    first,
                    second
            );

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    manager::close
            );

            assertTrue(failure.getMessage().contains("second"));
            assertEquals(1, failure.getSuppressed().length);
            assertEquals(
                    List.of("close:second", "close:first"),
                    events
            );
            assertFalse(resources.control.isShutdown());

            manager.close();
            assertThrows(IllegalStateException.class, manager::reconcile);
        }
    }

    private static JavaWorkerManager manager(
            WorkerExecutionResources resources,
            FakeWorker... workers
    ) {
        JavaWorkerManager.Builder builder = managerBuilder(
                resources,
                workers
        );
        for (int index = 0; index < workers.length; index++) {
            builder.replica(
                    "client-" + (index + 1),
                    WorkerIdentityStore.noCache(),
                    Collections::emptyMap
            );
        }
        return builder.build();
    }

    private static JavaWorkerManager.Builder managerBuilder(
            WorkerExecutionResources resources,
            FakeWorker... workers
    ) {
        Deque<WorkerLifecycle> assembled = new ArrayDeque<>();
        assembled.addAll(Arrays.asList(workers));
        return configuredBuilder(resources)
                .workerAssembler(ignored -> assembled.removeFirst());
    }

    private static JavaWorkerManager.Builder configuredBuilder(
            WorkerExecutionResources resources
    ) {
        return baseBuilder()
                .executionResources(resources)
                .eventDefinitions(definitions());
    }

    private static JavaWorkerManager.Builder baseBuilder() {
        return JavaWorkerManager.builder(
                URI.create("http://127.0.0.1:18082"),
                "group",
                WorkerTransportType.WEBSOCKET
        );
    }

    private static List<WorkerEventDefinition<?>> definitions() {
        return List.of(WorkerEventDefinition.of(
                "TASK",
                "test.manager",
                WorkerEventParameterResolvers.jsonMap(),
                parameters -> "null"
        ));
    }

    private static final class TestResources implements AutoCloseable {

        private final ExecutorService control =
                Executors.newSingleThreadExecutor();
        private final ExecutorService handler =
                Executors.newSingleThreadExecutor();
        private final WorkerExecutionResources resources =
                WorkerExecutionResources.of(control, handler);

        @Override
        public void close() {
            handler.shutdownNow();
            control.shutdownNow();
        }
    }

    private static final class FakeWorker implements WorkerLifecycle {

        private final String name;
        private final List<String> events;
        private State state = State.STOPPED;
        private int startCalls;
        private int startFailuresRemaining;
        private boolean completeStopImmediately = true;
        private boolean failClose;

        private FakeWorker(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }

        @Override
        public void start() {
            startCalls++;
            events.add("start:" + name);
            if (startFailuresRemaining > 0) {
                startFailuresRemaining--;
                throw new IllegalStateException("start " + name);
            }
            state = State.RUNNING;
        }

        @Override
        public void stop() {
            events.add("stop:" + name);
            if (completeStopImmediately) {
                state = State.STOPPED;
            }
        }

        @Override
        public Snapshot snapshot() {
            return new Snapshot(state, name, null, null);
        }

        @Override
        public void addListener(Listener listener) {
        }

        @Override
        public void removeListener(Listener listener) {
        }

        @Override
        public void close() {
            events.add("close:" + name);
            state = State.STOPPED;
            if (failClose) {
                throw new IllegalStateException("close " + name);
            }
        }

        private void terminate() {
            state = State.STOPPED;
        }

        private void finishStop() {
            state = State.STOPPED;
        }
    }
}
