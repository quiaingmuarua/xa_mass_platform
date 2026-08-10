package com.xa.mass.worker.javase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
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
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

class JavaWorkerManagerTest {

    @Test
    void buildsOneFixedOrderedGroupAndStartsEveryReplica() {
        List<String> events = new ArrayList<>();
        FakeWorker first = new FakeWorker("first", events);
        FakeWorker second = new FakeWorker("second", events);
        try (TestResources resources = new TestResources()) {
            JavaWorkerManager manager = managerBuilder(
                    resources.hostResources,
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
            resources.runControl();

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
                    .hostResources(resources.hostResources)
                    .eventDefinitions(definitions());
            assertThrows(IllegalStateException.class, empty::build);

            JavaWorkerManager.Builder duplicate = baseBuilder()
                    .hostResources(resources.hostResources)
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
                            .hostResources(resources.hostResources)
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
                    resources.hostResources,
                    first,
                    second
            );

            manager.start();
            resources.runControl();
            first.terminate();

            assertEquals(1, first.startCalls);
            assertEquals(1, second.startCalls);
            assertEquals(
                    WorkerLifecycle.State.STOPPED,
                    manager.snapshot("client-1").state()
            );

            manager.reconcile();
            resources.runControl();

            assertEquals(2, first.startCalls);
            assertEquals(1, second.startCalls);
            manager.close();
        }
    }

    @Test
    void repeatedReconcileCoalescesOneQueuedStartPerReplica() {
        FakeWorker worker = new FakeWorker("worker", new ArrayList<>());
        try (TestResources resources = new TestResources()) {
            JavaWorkerManager manager = manager(
                    resources.hostResources,
                    worker
            );

            manager.start();
            manager.reconcile();

            assertEquals(1, resources.control.pendingTasks());
            resources.runControl();
            assertEquals(1, worker.startCalls);
            manager.close();
        }
    }

    @Test
    void stopBeforeQueuedStartPreventsWorkerStartup() {
        FakeWorker worker = new FakeWorker("worker", new ArrayList<>());
        try (TestResources resources = new TestResources()) {
            JavaWorkerManager manager = manager(
                    resources.hostResources,
                    worker
            );

            manager.start();
            manager.stop();
            resources.runControl();

            assertEquals(0, worker.startCalls);
            assertEquals(WorkerLifecycle.State.STOPPED, worker.state);
            manager.close();
        }
    }

    @Test
    void rejectedControlSubmissionCanBeExplicitlyReconciledLater() {
        FakeWorker worker = new FakeWorker("worker", new ArrayList<>());
        try (TestResources resources = new TestResources()) {
            JavaWorkerManager manager = manager(
                    resources.hostResources,
                    worker
            );
            resources.control.reject(true);

            assertThrows(RejectedExecutionException.class, manager::start);
            assertEquals(0, resources.control.pendingTasks());

            resources.control.reject(false);
            manager.reconcile();
            resources.runControl();
            assertEquals(1, worker.startCalls);
            manager.close();
        }
    }

    @Test
    void startDuringGracefulStopWaitsForLaterReconcile() {
        FakeWorker worker = new FakeWorker("worker", new ArrayList<>());
        worker.completeStopImmediately = false;
        try (TestResources resources = new TestResources()) {
            JavaWorkerManager manager = manager(
                    resources.hostResources,
                    worker
            );

            manager.start();
            resources.runControl();
            manager.stop();
            manager.start();

            assertEquals(1, worker.startCalls);
            assertEquals(WorkerLifecycle.State.RUNNING, worker.state);

            worker.finishStop();
            assertEquals(1, worker.startCalls);

            manager.reconcile();
            resources.runControl();
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
                    resources.hostResources,
                    first,
                    second,
                    third
            );

            manager.start();
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    resources::runControl
            );

            assertTrue(failure.getMessage().contains("first"));
            assertEquals(1, failure.getSuppressed().length);
            assertEquals(1, first.startCalls);
            assertEquals(1, second.startCalls);
            assertEquals(1, third.startCalls);

            manager.reconcile();
            resources.runControl();

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
                    resources.hostResources
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
                    resources.hostResources,
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
            JavaWorkerHostResources resources,
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
            JavaWorkerHostResources resources,
            FakeWorker... workers
    ) {
        Deque<WorkerLifecycle> assembled = new ArrayDeque<>();
        assembled.addAll(Arrays.asList(workers));
        return configuredBuilder(resources)
                .workerAssembler(ignored -> assembled.removeFirst());
    }

    private static JavaWorkerManager.Builder configuredBuilder(
            JavaWorkerHostResources resources
    ) {
        return baseBuilder()
                .hostResources(resources)
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

        private final ManualExecutorService control =
                new ManualExecutorService();
        private final JavaWorkerHostResources hostResources =
                new JavaWorkerHostResources(control);

        private void runControl() {
            control.runAll();
        }

        @Override
        public void close() {
            hostResources.close();
        }
    }

    private static final class ManualExecutorService
            extends AbstractExecutorService {

        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;
        private boolean reject;

        @Override
        public synchronized void shutdown() {
            shutdown = true;
        }

        @Override
        public synchronized List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> queued = new ArrayList<>(tasks);
            tasks.clear();
            return queued;
        }

        @Override
        public synchronized boolean isShutdown() {
            return shutdown;
        }

        @Override
        public synchronized boolean isTerminated() {
            return shutdown && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }

        @Override
        public synchronized void execute(Runnable command) {
            if (shutdown || reject) {
                throw new RejectedExecutionException();
            }
            tasks.add(command);
        }

        private void runAll() {
            RuntimeException failure = null;
            while (true) {
                Runnable task;
                synchronized (this) {
                    task = tasks.poll();
                }
                if (task == null) {
                    break;
                }
                try {
                    task.run();
                } catch (RuntimeException error) {
                    if (failure == null) {
                        failure = error;
                    } else {
                        failure.addSuppressed(error);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private synchronized int pendingTasks() {
            return tasks.size();
        }

        private synchronized void reject(boolean value) {
            reject = value;
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
