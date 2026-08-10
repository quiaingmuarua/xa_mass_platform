package com.xa.mass.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.transport.client.TextMessageClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class WorkerRunControllerTest {

    private static final String WORKER_ID =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";
    private static final URI ENDPOINT = URI.create(
            "ws://127.0.0.1:18083/worker"
    );

    private final List<WorkerRunController> controllers =
            new ArrayList<>();
    private final TestWorkerExecutionResources executions =
            new TestWorkerExecutionResources();

    @AfterEach
    void tearDown() {
        for (WorkerRunController controller : controllers) {
            controller.close();
        }
        executions.close();
    }

    @Test
    void startSubmitsOnePreparationAndDuplicateStartIsIdempotent()
            throws Exception {
        BlockingPreparation preparation = new BlockingPreparation();
        FakeNetworkFactory networks = new FakeNetworkFactory();
        WorkerRunController controller = controller(
                preparation,
                networks,
                executions.resources()
        );

        controller.start();
        assertEquals(
                WorkerLifecycle.State.RUNNING,
                controller.snapshot().state()
        );
        assertTrue(preparation.entered.await(5, TimeUnit.SECONDS));

        controller.start();
        assertEquals(1, preparation.calls.get());

        preparation.release.countDown();
        networks.awaitClient(0);
        assertEquals(1, preparation.calls.get());
        assertEquals(WORKER_ID, controller.snapshot().workerId());
        assertEquals(ENDPOINT, controller.snapshot().endpointUri());
    }

    @Test
    void failedPreparationStopsWithoutRetryUntilExplicitStart()
            throws Exception {
        ScriptedPreparation preparation = new ScriptedPreparation(1);
        FakeNetworkFactory networks = new FakeNetworkFactory();
        WorkerRunController controller = controller(
                preparation,
                networks,
                executions.resources()
        );

        controller.start();
        awaitStopped(controller);
        assertEquals(1, preparation.calls.get());
        assertTrue(networks.clients.isEmpty());
        assertTrue(controller.snapshot().diagnosticMessage()
                .contains("IOException"));

        Thread.sleep(100L);
        assertEquals(1, preparation.calls.get());

        controller.start();
        networks.awaitClient(0);
        assertEquals(2, preparation.calls.get());
        assertEquals(
                WorkerLifecycle.State.RUNNING,
                controller.snapshot().state()
        );
    }

    @Test
    void endpointTerminationStopsWithoutStartingAnotherRun()
            throws Exception {
        ScriptedPreparation preparation = new ScriptedPreparation(0);
        FakeNetworkFactory networks = new FakeNetworkFactory();
        WorkerRunController controller = controller(
                preparation,
                networks,
                executions.resources()
        );

        controller.start();
        FakeTextMessageClient client = networks.awaitClient(0);
        client.terminate();
        awaitStopped(controller);

        assertEquals(1, preparation.calls.get());
        assertEquals("Endpoint terminated",
                controller.snapshot().diagnosticMessage());
        Thread.sleep(100L);
        assertEquals(1, preparation.calls.get());
    }

    @Test
    void stopWhilePreparationIsQueuedDropsTheStartAtItsBoundary()
            throws Exception {
        ExecutorService control = Executors.newSingleThreadExecutor();
        ExecutorService handler = Executors.newSingleThreadExecutor();
        CountDownLatch blockerEntered = new CountDownLatch(1);
        CountDownLatch blockerRelease = new CountDownLatch(1);
        control.execute(() -> {
            blockerEntered.countDown();
            awaitLatch(blockerRelease);
        });
        assertTrue(blockerEntered.await(5, TimeUnit.SECONDS));

        ScriptedPreparation preparation = new ScriptedPreparation(0);
        FakeNetworkFactory networks = new FakeNetworkFactory();
        WorkerRunController controller = controller(
                preparation,
                networks,
                WorkerExecutionResources.of(control, handler)
        );
        try {
            controller.start();
            controller.stop();
            assertEquals(
                    WorkerLifecycle.State.RUNNING,
                    controller.snapshot().state()
            );

            blockerRelease.countDown();
            awaitStopped(controller);
            assertEquals(0, preparation.calls.get());
            assertTrue(networks.clients.isEmpty());
        } finally {
            blockerRelease.countDown();
            controller.close();
            handler.shutdownNow();
            control.shutdownNow();
        }
    }

    @Test
    void stopDuringPreparationDiscardsItsResultWithoutInterruption()
            throws Exception {
        BlockingPreparation preparation = new BlockingPreparation();
        FakeNetworkFactory networks = new FakeNetworkFactory();
        WorkerRunController controller = controller(
                preparation,
                networks,
                executions.resources()
        );

        controller.start();
        assertTrue(preparation.entered.await(5, TimeUnit.SECONDS));
        controller.stop();
        assertEquals(
                WorkerLifecycle.State.RUNNING,
                controller.snapshot().state()
        );

        preparation.release.countDown();
        awaitStopped(controller);
        assertTrue(networks.clients.isEmpty());
        assertEquals(1, preparation.calls.get());
    }

    @Test
    void closeDuringPreparationEndsTheObjectWithoutCreatingClient()
            throws Exception {
        BlockingPreparation preparation = new BlockingPreparation();
        FakeNetworkFactory networks = new FakeNetworkFactory();
        WorkerRunController controller = controller(
                preparation,
                networks,
                executions.resources()
        );

        controller.start();
        assertTrue(preparation.entered.await(5, TimeUnit.SECONDS));
        controller.close();
        awaitStopped(controller);

        assertTrue(networks.clients.isEmpty());
        assertThrows(IllegalStateException.class, controller::start);
    }

    @Test
    void stopRequestsCurrentRuntimeTermination() throws Exception {
        FakeNetworkFactory networks = new FakeNetworkFactory();
        WorkerRunController controller = controller(
                new ScriptedPreparation(0),
                networks,
                executions.resources()
        );

        controller.start();
        FakeTextMessageClient client = networks.awaitClient(0);
        controller.stop();
        awaitStopped(controller);

        assertEquals(1, client.closeCalls.get());
        assertEquals(null, controller.snapshot().diagnosticMessage());
    }

    @Test
    void rejectedControlSubmissionRestoresStoppedState() {
        ExecutorService control = Executors.newSingleThreadExecutor();
        ExecutorService handler = Executors.newSingleThreadExecutor();
        control.shutdownNow();
        WorkerRunController controller = controller(
                new ScriptedPreparation(0),
                new FakeNetworkFactory(),
                WorkerExecutionResources.of(control, handler)
        );
        try {
            assertThrows(IllegalStateException.class, controller::start);
            assertEquals(
                    WorkerLifecycle.State.STOPPED,
                    controller.snapshot().state()
            );
        } finally {
            controller.close();
            handler.shutdownNow();
        }
    }

    @Test
    void runtimeStartFailureStopsAndClosesInstalledRuntime()
            throws Exception {
        FakeTextMessageClient client = new FakeTextMessageClient(
                new IllegalStateException("start failed")
        );
        WorkerRunController controller = controller(
                new ScriptedPreparation(0),
                endpointUri -> client,
                executions.resources()
        );

        controller.start();
        awaitStopped(controller);

        assertEquals(1, client.closeCalls.get());
        assertTrue(controller.snapshot().diagnosticMessage()
                .contains("IllegalStateException"));
    }

    @Test
    void preparationErrorCleansStateBeforeItEscapesExecutor() {
        ManualExecutorService control = new ManualExecutorService();
        ExecutorService handler = Executors.newSingleThreadExecutor();
        WorkerPreparation preparation = new WorkerPreparation() {
            @Override
            public PreparedWorker prepare() {
                throw new AssertionError("fatal prepare");
            }

            @Override
            public void close() {
            }
        };
        WorkerRunController controller = controller(
                preparation,
                new FakeNetworkFactory(),
                WorkerExecutionResources.of(control, handler)
        );
        try {
            controller.start();
            assertThrows(AssertionError.class, control::runNext);
            assertEquals(
                    WorkerLifecycle.State.STOPPED,
                    controller.snapshot().state()
            );
        } finally {
            controller.close();
            handler.shutdownNow();
            control.shutdownNow();
        }
    }

    @Test
    void listenerErrorObservesAlreadyCommittedTerminalState()
            throws Exception {
        FakeNetworkFactory networks = new FakeNetworkFactory();
        WorkerRunController controller = controller(
                new ScriptedPreparation(0),
                networks,
                executions.resources()
        );
        AtomicBoolean failOnStopped = new AtomicBoolean();
        WorkerLifecycle.Listener listener = snapshot -> {
            if (failOnStopped.get()
                    && snapshot.state() == WorkerLifecycle.State.STOPPED) {
                throw new AssertionError("fatal listener");
            }
        };
        controller.addListener(listener);
        controller.start();
        FakeTextMessageClient client = networks.awaitClient(0);
        failOnStopped.set(true);

        assertThrows(AssertionError.class, client::terminate);
        assertEquals(
                WorkerLifecycle.State.STOPPED,
                controller.snapshot().state()
        );
        controller.removeListener(listener);
    }

    @Test
    void closeDoesNotShutHostExecutorsDown() throws Exception {
        WorkerRunController controller = controller(
                new ScriptedPreparation(0),
                new FakeNetworkFactory(),
                executions.resources()
        );
        controller.close();

        assertFalse(executions.controlExecutor().isShutdown());
        assertFalse(executions.handlerExecutor().isShutdown());
        assertThrows(IllegalStateException.class, controller::start);
    }

    private WorkerRunController controller(
            WorkerPreparation preparation,
            WorkerRunController.NetworkClientFactory networks,
            WorkerExecutionResources resources
    ) {
        WorkerRunController controller = new WorkerRunController(
                preparation,
                command -> java.util.Optional.empty(),
                networks,
                resources
        );
        controllers.add(controller);
        return controller;
    }

    private static void awaitStopped(WorkerRunController controller)
            throws Exception {
        await(() -> controller.snapshot().state()
                == WorkerLifecycle.State.STOPPED);
    }

    private static void await(Check check) throws Exception {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (check.isSatisfied()) {
                return;
            }
            Thread.sleep(10L);
        }
        assertTrue(check.isSatisfied(), "condition was not satisfied");
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

    @FunctionalInterface
    private interface Check {
        boolean isSatisfied();
    }

    private static final class ScriptedPreparation
            implements WorkerPreparation {

        private final AtomicInteger failuresRemaining;
        private final AtomicInteger calls = new AtomicInteger();

        private ScriptedPreparation(int failures) {
            failuresRemaining = new AtomicInteger(failures);
        }

        @Override
        public PreparedWorker prepare() throws Exception {
            calls.incrementAndGet();
            if (failuresRemaining.getAndUpdate(value ->
                    Math.max(0, value - 1)) > 0) {
                throw new IOException("unavailable");
            }
            return new PreparedWorker(WORKER_ID, ENDPOINT);
        }

        @Override
        public void close() {
        }
    }

    private static final class BlockingPreparation
            implements WorkerPreparation {

        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public PreparedWorker prepare() {
            calls.incrementAndGet();
            entered.countDown();
            awaitLatch(release);
            return new PreparedWorker(WORKER_ID, ENDPOINT);
        }

        @Override
        public void close() {
            release.countDown();
        }
    }

    private static final class FakeNetworkFactory
            implements WorkerRunController.NetworkClientFactory {

        private final List<FakeTextMessageClient> clients =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public TextMessageClient create(URI endpointUri) {
            FakeTextMessageClient client = new FakeTextMessageClient();
            clients.add(client);
            return client;
        }

        private FakeTextMessageClient awaitClient(int index)
                throws Exception {
            await(() -> clients.size() > index
                    && clients.get(index).listener != null);
            return clients.get(index);
        }
    }

    private static final class FakeTextMessageClient
            implements TextMessageClient {

        private final AtomicInteger closeCalls = new AtomicInteger();
        private final RuntimeException startFailure;
        private volatile Listener listener;

        private FakeTextMessageClient() {
            this(null);
        }

        private FakeTextMessageClient(RuntimeException startFailure) {
            this.startFailure = startFailure;
        }

        @Override
        public void start(Listener value) {
            listener = value;
            if (startFailure != null) {
                throw startFailure;
            }
        }

        @Override
        public boolean send(String message) {
            return true;
        }

        @Override
        public void closeCurrent(CloseReason reason) {
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }

        private void terminate() {
            listener.onEndpointTerminated();
        }
    }

    private static final class ManualExecutorService
            extends AbstractExecutorService {

        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;

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
            if (shutdown) {
                throw new RejectedExecutionException();
            }
            tasks.add(command);
        }

        private void runNext() {
            Runnable task;
            synchronized (this) {
                task = tasks.remove();
            }
            task.run();
        }
    }
}
