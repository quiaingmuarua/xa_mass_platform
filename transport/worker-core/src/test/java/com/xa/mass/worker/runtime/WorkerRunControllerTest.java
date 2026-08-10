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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    private final TestHandlerExecutor handler = new TestHandlerExecutor();
    private final ExecutorService startExecutor =
            Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        for (WorkerRunController controller : controllers) {
            controller.close();
        }
        startExecutor.shutdownNow();
        handler.close();
    }

    @Test
    void startRunsOnePreparationSynchronouslyAndDuplicateIsIdempotent()
            throws Exception {
        BlockingPreparation preparation = new BlockingPreparation();
        FakeNetworkFactory networks = new FakeNetworkFactory();
        WorkerRunController controller = controller(
                preparation,
                networks,
                handler.executor()
        );
        CountDownLatch runningObserved = new CountDownLatch(1);
        controller.addListener(snapshot -> {
            if (snapshot.state() == WorkerLifecycle.State.RUNNING) {
                runningObserved.countDown();
            }
        });

        Future<?> starting = startExecutor.submit(controller::start);
        assertTrue(runningObserved.await(5, TimeUnit.SECONDS));
        assertTrue(preparation.entered.await(5, TimeUnit.SECONDS));
        assertFalse(starting.isDone());
        assertEquals(
                WorkerLifecycle.State.RUNNING,
                controller.snapshot().state()
        );

        controller.start();
        assertEquals(1, preparation.calls.get());

        preparation.release.countDown();
        starting.get(5, TimeUnit.SECONDS);
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
                handler.executor()
        );

        controller.start();
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
                handler.executor()
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
    void stopDuringPreparationDiscardsItsResultWithoutInterruption()
            throws Exception {
        BlockingPreparation preparation = new BlockingPreparation();
        FakeNetworkFactory networks = new FakeNetworkFactory();
        WorkerRunController controller = controller(
                preparation,
                networks,
                handler.executor()
        );

        Future<?> starting = startExecutor.submit(controller::start);
        assertTrue(preparation.entered.await(5, TimeUnit.SECONDS));
        controller.stop();
        assertEquals(
                WorkerLifecycle.State.RUNNING,
                controller.snapshot().state()
        );

        preparation.release.countDown();
        starting.get(5, TimeUnit.SECONDS);
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
                handler.executor()
        );

        Future<?> starting = startExecutor.submit(controller::start);
        assertTrue(preparation.entered.await(5, TimeUnit.SECONDS));
        controller.close();
        starting.get(5, TimeUnit.SECONDS);
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
                handler.executor()
        );

        controller.start();
        FakeTextMessageClient client = networks.awaitClient(0);
        controller.stop();
        awaitStopped(controller);

        assertEquals(1, client.closeCalls.get());
        assertEquals(null, controller.snapshot().diagnosticMessage());
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
                handler.executor()
        );

        controller.start();

        assertEquals(1, client.closeCalls.get());
        assertTrue(controller.snapshot().diagnosticMessage()
                .contains("IllegalStateException"));
    }

    @Test
    void preparationErrorCleansStateBeforeItEscapesStart() {
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
                handler.executor()
        );

        assertThrows(AssertionError.class, controller::start);
        assertEquals(
                WorkerLifecycle.State.STOPPED,
                controller.snapshot().state()
        );
    }

    @Test
    void listenerErrorAfterRuntimeInstallCleansStateBeforeItEscapes()
            throws Exception {
        FakeNetworkFactory networks = new FakeNetworkFactory();
        WorkerRunController controller = controller(
                new ScriptedPreparation(0),
                networks,
                handler.executor()
        );
        AtomicInteger runningNotifications = new AtomicInteger();
        controller.addListener(snapshot -> {
            if (snapshot.state() == WorkerLifecycle.State.RUNNING
                    && runningNotifications.incrementAndGet() == 2) {
                throw new AssertionError("fatal listener");
            }
        });

        assertThrows(AssertionError.class, controller::start);

        assertEquals(
                WorkerLifecycle.State.STOPPED,
                controller.snapshot().state()
        );
        assertEquals(1, networks.clients.get(0).closeCalls.get());
    }

    @Test
    void listenerErrorObservesAlreadyCommittedTerminalState()
            throws Exception {
        FakeNetworkFactory networks = new FakeNetworkFactory();
        WorkerRunController controller = controller(
                new ScriptedPreparation(0),
                networks,
                handler.executor()
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
    void closeDoesNotShutHostHandlerExecutorDown() {
        WorkerRunController controller = controller(
                new ScriptedPreparation(0),
                new FakeNetworkFactory(),
                handler.executor()
        );
        controller.close();

        assertFalse(handler.executor().isShutdown());
        assertThrows(IllegalStateException.class, controller::start);
    }

    private WorkerRunController controller(
            WorkerPreparation preparation,
            WorkerRunController.NetworkClientFactory networks,
            Executor handlerExecutor
    ) {
        WorkerRunController controller = new WorkerRunController(
                preparation,
                command -> java.util.Optional.empty(),
                networks,
                handlerExecutor
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

}
