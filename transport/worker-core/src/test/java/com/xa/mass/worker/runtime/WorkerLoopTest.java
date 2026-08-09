package com.xa.mass.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.worker.execution.WorkerCommandDispatcher;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventHandler;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class WorkerLoopTest {

    private static final String WORKER_ID =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";
    private static final URI ENDPOINT = URI.create(
            "ws://127.0.0.1:18083/worker"
    );
    private static final WorkerDeliveryCodec CODEC =
            new WorkerDeliveryCodec();

    private final List<WorkerLoop> loops = new ArrayList<>();
    private final TestWorkerExecutionResources executions =
            new TestWorkerExecutionResources();

    @AfterEach
    void tearDown() {
        for (WorkerLoop loop : loops) {
            loop.close();
        }
        executions.close();
    }

    @Test
    void acceptedStartIsRunningThroughoutPreparation() throws Exception {
        BlockingPreparation preparation = new BlockingPreparation();
        FakeNetworkFactory networks = new FakeNetworkFactory();
        WorkerLoop loop = loop(
                preparation,
                payload -> payload,
                networks,
                policy(1)
        );

        loop.start();
        assertEquals(WorkerLifecycle.State.RUNNING, loop.snapshot().state());
        assertEquals(
                WorkerLifecycle.ConnectionState.DISCONNECTED,
                loop.snapshot().connectionState()
        );
        assertTrue(preparation.entered.await(5, TimeUnit.SECONDS));

        loop.start();
        assertEquals(1, preparation.calls.get());

        preparation.release.countDown();
        networks.awaitClient(0);
        assertEquals(1, preparation.calls.get());
        assertEquals(1, networks.clients.size());
    }

    @Test
    void runningIncludesRuntimeStart() throws Exception {
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch startRelease = new CountDownLatch(1);
        FakeTextMessageClient client = new FakeTextMessageClient(
                startEntered,
                startRelease
        );
        WorkerLoop loop = new WorkerLoop(
                new FakePreparation(0),
                command -> Optional.empty(),
                endpoint -> client,
                policy(1),
                executions.resources()
        );
        loops.add(loop);

        loop.start();
        assertTrue(startEntered.await(5, TimeUnit.SECONDS));
        assertEquals(WorkerLifecycle.State.RUNNING, loop.snapshot().state());
        assertEquals(
                WorkerLifecycle.ConnectionState.CONNECTING,
                loop.snapshot().connectionState()
        );

        loop.start();
        startRelease.countDown();
        await(() -> client.listener != null);
    }

    @Test
    void systemAndTaskCommandsEnterThroughTheInboundConnection()
            throws Exception {
        AtomicInteger executions = new AtomicInteger();
        FakePreparation preparation = new FakePreparation(0);
        FakeNetworkFactory networks = new FakeNetworkFactory();
        WorkerLoop loop = loop(
                preparation,
                payload -> {
                    executions.incrementAndGet();
                    return payload;
                },
                networks,
                policy(1)
        );

        loop.start();
        FakeTextMessageClient client = networks.awaitClient(0);
        client.open();
        await(loop::isConnected);

        client.message(CODEC.encodeWorkerCommand(systemCommand(
                "95992d31-9a9b-44b0-bd0a-1cfa18bb4402"
        )));
        await(() -> client.sent.size() == 2);
        client.message(CODEC.encodeWorkerCommand(command(
                "6cae656c-2f1c-495d-abce-07a945c69b3d"
        )));
        await(() -> client.sent.size() == 3);

        assertEquals(2, executions.get());
        assertEquals(
                "95992d31-9a9b-44b0-bd0a-1cfa18bb4402",
                CODEC.decodeWorkerResult(client.sent.get(1)).messageId()
        );
        assertEquals(
                WorkerMessageEndpoint.SYSTEM,
                CODEC.decodeWorkerResult(client.sent.get(1)).dst()
        );
        assertEquals(
                "6cae656c-2f1c-495d-abce-07a945c69b3d",
                CODEC.decodeWorkerResult(client.sent.get(2)).messageId()
        );
    }

    @Test
    void busyInboundCommandClosesConnectionWithoutQueueingIt()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        FakeNetworkFactory networks = new FakeNetworkFactory();
        WorkerLoop loop = loop(
                new FakePreparation(0),
                payload -> {
                    executions.incrementAndGet();
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return payload;
                },
                networks,
                policy(1)
        );
        loop.start();
        FakeTextMessageClient client = networks.awaitClient(0);
        client.open();
        await(loop::isConnected);

        client.message(CODEC.encodeWorkerCommand(command(
                "95992d31-9a9b-44b0-bd0a-1cfa18bb4402"
        )));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        client.message(CODEC.encodeWorkerCommand(command(
                "6cae656c-2f1c-495d-abce-07a945c69b3d"
        )));

        assertFalse(loop.isConnected());
        release.countDown();
        await(() -> executions.get() == 1);
        assertEquals(1, executions.get());
    }

    @Test
    void endpointTerminationWaitsForCommandThenStopsAndDropsResult()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakePreparation preparation = new FakePreparation(0);
        FakeNetworkFactory networks = new FakeNetworkFactory();
        WorkerLoop loop = loop(
                preparation,
                payload -> {
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return payload;
                },
                networks,
                policy(1)
        );
        loop.start();
        FakeTextMessageClient client = networks.awaitClient(0);
        client.open();
        await(loop::isConnected);
        client.message(CODEC.encodeWorkerCommand(command(
                "95992d31-9a9b-44b0-bd0a-1cfa18bb4402"
        )));
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        client.terminateEndpoint();
        assertEquals(WorkerLifecycle.State.RUNNING, loop.snapshot().state());
        assertEquals(
                WorkerLifecycle.ConnectionState.DISCONNECTED,
                loop.snapshot().connectionState()
        );
        loop.start();
        assertEquals(1, preparation.calls.get());

        release.countDown();
        await(() -> loop.snapshot().state()
                == WorkerLifecycle.State.STOPPED);

        assertEquals(1, preparation.calls.get());
        assertEquals(1, networks.clients.size());
        assertEquals(1, client.sent.size());
    }

    @Test
    void endpointTerminationStopsUntilTheNextExplicitStart()
            throws Exception {
        FakePreparation preparation = new FakePreparation(0);
        FakeNetworkFactory networks = new FakeNetworkFactory();
        WorkerLoop loop = loop(
                preparation,
                payload -> payload,
                networks,
                policy(1)
        );
        loop.start();
        FakeTextMessageClient first = networks.awaitClient(0);

        first.terminateEndpoint();
        first.terminateEndpoint();
        await(() -> loop.snapshot().state()
                == WorkerLifecycle.State.STOPPED);
        Thread.sleep(50);

        assertEquals(1, preparation.calls.get());
        assertEquals(1, networks.clients.size());
        assertEquals("Endpoint terminated",
                loop.snapshot().diagnosticMessage());

        loop.start();
        networks.awaitClient(1);
        assertEquals(2, preparation.calls.get());
        assertEquals(2, networks.clients.size());
    }

    @Test
    void endpointCleanupRemainsRunningUntilOldRuntimeIsClosed()
            throws Exception {
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        FakeTextMessageClient client = new FakeTextMessageClient(
                null,
                null,
                closeEntered,
                releaseClose
        );
        FakePreparation preparation = new FakePreparation(0);
        WorkerLoop loop = new WorkerLoop(
                preparation,
                command -> Optional.empty(),
                endpoint -> client,
                policy(1),
                executions.resources()
        );
        loops.add(loop);
        ExecutorService callbackCaller =
                Executors.newSingleThreadExecutor();

        try {
            loop.start();
            await(() -> client.listener != null);
            Future<?> termination = callbackCaller.submit(
                    client::terminateEndpoint
            );
            assertTrue(closeEntered.await(5, TimeUnit.SECONDS));

            assertEquals(
                    WorkerLifecycle.State.RUNNING,
                    loop.snapshot().state()
            );
            loop.start();
            assertEquals(1, preparation.calls.get());

            releaseClose.countDown();
            termination.get(5, TimeUnit.SECONDS);
            await(() -> loop.snapshot().state()
                    == WorkerLifecycle.State.STOPPED);
        } finally {
            releaseClose.countDown();
            callbackCaller.shutdownNow();
        }
    }

    @Test
    void ordinaryReconnectStaysInsideCurrentRun() throws Exception {
        FakePreparation preparation = new FakePreparation(0);
        FakeNetworkFactory networks = new FakeNetworkFactory();
        WorkerLoop loop = loop(
                preparation,
                payload -> payload,
                networks,
                policy(1)
        );
        loop.start();
        FakeTextMessageClient client = networks.awaitClient(0);
        client.open();
        await(loop::isConnected);

        client.disconnect();
        await(() -> !loop.isConnected());
        client.open();
        await(loop::isConnected);

        assertEquals(WorkerLifecycle.State.RUNNING, loop.snapshot().state());
        assertEquals(1, preparation.calls.get());
        assertEquals(1, networks.clients.size());
    }

    @Test
    void preparationRetriesAreBoundedAndFailureNeedsExplicitRestart()
            throws Exception {
        FakePreparation preparation = new FakePreparation(2);
        FakeNetworkFactory networks = new FakeNetworkFactory();
        WorkerLoop loop = loop(
                preparation,
                payload -> payload,
                networks,
                policy(2)
        );

        loop.start();
        await(() -> preparation.calls.get() == 2
                && loop.snapshot().state()
                == WorkerLifecycle.State.STOPPED);
        assertEquals(0, networks.clients.size());
        assertTrue(loop.snapshot().diagnosticMessage().contains(
                "control unavailable"
        ));

        loop.start();
        networks.awaitClient(0);
        assertEquals(3, preparation.calls.get());
        assertEquals(WorkerLifecycle.State.RUNNING, loop.snapshot().state());
    }

    @Test
    void nonRetryablePreparationFailureStopsImmediately()
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        WorkerPreparation preparation = new WorkerPreparation() {
            @Override
            public PreparedWorker prepare() {
                calls.incrementAndGet();
                throw new IllegalArgumentException("invalid identity");
            }

            @Override
            public void close() {
            }
        };
        WorkerLoop loop = loop(
                preparation,
                payload -> payload,
                new FakeNetworkFactory(),
                policy(10)
        );

        loop.start();
        await(() -> calls.get() == 1
                && loop.snapshot().state()
                == WorkerLifecycle.State.STOPPED);

        assertTrue(loop.snapshot().diagnosticMessage().contains(
                "invalid identity"
        ));
    }

    @Test
    void stopCancelsScheduledPreparationRetry() throws Exception {
        FakePreparation preparation = new FakePreparation(100);
        FakeNetworkFactory networks = new FakeNetworkFactory();
        WorkerLoop loop = loop(
                preparation,
                payload -> payload,
                networks,
                WorkerRetryPolicy.of(
                        100,
                        Duration.ofMillis(200),
                        TextMessageReconnectPolicy.of(
                                1,
                                Duration.ofMillis(1),
                                Duration.ofMillis(1)
                        )
                )
        );

        loop.start();
        await(() -> preparation.calls.get() == 1);
        loop.stop();
        await(() -> loop.snapshot().state()
                == WorkerLifecycle.State.STOPPED);
        Thread.sleep(250);

        assertEquals(1, preparation.calls.get());
        assertEquals(0, networks.clients.size());
    }

    @Test
    void stopCancelsPreparationQueuedInSharedControlPool()
            throws Exception {
        CountDownLatch controlsEntered = new CountDownLatch(2);
        CountDownLatch releaseControls = new CountDownLatch(1);
        for (int index = 0; index < 2; index++) {
            executions.controlExecutor().execute(() -> {
                controlsEntered.countDown();
                try {
                    releaseControls.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(controlsEntered.await(5, TimeUnit.SECONDS));
        FakePreparation preparation = new FakePreparation(0);
        WorkerLoop loop = loop(
                preparation,
                payload -> payload,
                new FakeNetworkFactory(),
                policy(1)
        );

        try {
            loop.start();
            assertEquals(
                    WorkerLifecycle.State.RUNNING,
                    loop.snapshot().state()
            );

            loop.stop();
            assertEquals(
                    WorkerLifecycle.State.STOPPED,
                    loop.snapshot().state()
            );
        } finally {
            releaseControls.countDown();
        }
        Thread.sleep(50);

        assertEquals(0, preparation.calls.get());
    }

    @Test
    void closingOneWorkerDoesNotCloseSharedExecutionResources()
            throws Exception {
        FakeNetworkFactory firstNetworks = new FakeNetworkFactory();
        WorkerLoop first = loop(
                new FakePreparation(0),
                payload -> payload,
                firstNetworks,
                policy(1)
        );
        FakeNetworkFactory secondNetworks = new FakeNetworkFactory();
        WorkerLoop second = loop(
                new FakePreparation(0),
                payload -> payload,
                secondNetworks,
                policy(1)
        );

        first.start();
        firstNetworks.awaitClient(0);
        first.close();

        assertFalse(executions.controlExecutor().isShutdown());
        assertFalse(executions.handlerExecutor().isShutdown());
        assertFalse(executions.retryScheduler().isShutdown());
        second.start();
        secondNetworks.awaitClient(0);
        assertEquals(
                WorkerLifecycle.State.RUNNING,
                second.snapshot().state()
        );
    }

    @Test
    void rejectedControlSubmissionRestoresStoppedAndFailsStart() {
        ExecutorService rejectedControl =
                Executors.newSingleThreadExecutor();
        rejectedControl.shutdownNow();
        FakePreparation preparation = new FakePreparation(0);
        WorkerLoop loop = new WorkerLoop(
                preparation,
                command -> Optional.empty(),
                new FakeNetworkFactory(),
                policy(1),
                WorkerExecutionResources.of(
                        rejectedControl,
                        executions.handlerExecutor(),
                        executions.retryScheduler()
                )
        );
        loops.add(loop);

        assertThrows(IllegalStateException.class, loop::start);
        assertEquals(
                WorkerLifecycle.State.STOPPED,
                loop.snapshot().state()
        );
        assertEquals(
                "Worker control executor is unavailable",
                loop.snapshot().diagnosticMessage()
        );
        assertEquals(0, preparation.calls.get());
    }

    @Test
    void slowListenerRunsSynchronouslyOutsideStateLockAndCoalesces()
            throws Exception {
        FakeNetworkFactory networks = new FakeNetworkFactory();
        WorkerLoop loop = loop(
                new FakePreparation(0),
                payload -> payload,
                networks,
                policy(1)
        );
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        List<WorkerLifecycle.Snapshot> snapshots =
                new CopyOnWriteArrayList<>();
        ExecutorService listenerCaller =
                Executors.newSingleThreadExecutor();

        try {
            Future<?> registration = listenerCaller.submit(() -> {
                Thread registrationThread = Thread.currentThread();
                loop.addListener(snapshot -> {
                    callbackThread.compareAndSet(null, Thread.currentThread());
                    snapshots.add(snapshot);
                    if (callbacks.getAndIncrement() == 0) {
                        listenerEntered.countDown();
                        try {
                            releaseListener.await();
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
                assertSame(registrationThread, callbackThread.get());
            });
            assertTrue(listenerEntered.await(5, TimeUnit.SECONDS));

            loop.start();
            assertEquals(
                    WorkerLifecycle.State.RUNNING,
                    loop.snapshot().state()
            );
            networks.awaitClient(0);
            assertFalse(registration.isDone());

            releaseListener.countDown();
            registration.get(5, TimeUnit.SECONDS);
            assertEquals(2, snapshots.size());
            assertEquals(
                    WorkerLifecycle.State.STOPPED,
                    snapshots.get(0).state()
            );
            assertEquals(
                    WorkerLifecycle.State.RUNNING,
                    snapshots.get(1).state()
            );
        } finally {
            releaseListener.countDown();
            listenerCaller.shutdownNow();
        }
    }

    @Test
    void stopDuringPreparationPreventsRuntimeInstallation()
            throws Exception {
        BlockingPreparation preparation = new BlockingPreparation();
        FakeNetworkFactory networks = new FakeNetworkFactory();
        WorkerLoop loop = loop(
                preparation,
                payload -> payload,
                networks,
                policy(1)
        );

        loop.start();
        assertTrue(preparation.entered.await(5, TimeUnit.SECONDS));
        loop.stop();
        loop.start();
        assertEquals(WorkerLifecycle.State.RUNNING, loop.snapshot().state());

        preparation.release.countDown();
        await(() -> loop.snapshot().state()
                == WorkerLifecycle.State.STOPPED);
        assertEquals(1, preparation.calls.get());
        assertEquals(0, networks.clients.size());

        loop.start();
        networks.awaitClient(0);
        assertEquals(2, preparation.calls.get());
    }

    @Test
    void closeInterruptsRunningPreparationWithoutClosingSharedPool()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        WorkerPreparation preparation = new WorkerPreparation() {
            @Override
            public PreparedWorker prepare() throws Exception {
                entered.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException error) {
                    interrupted.countDown();
                    throw error;
                }
                throw new AssertionError("unreachable");
            }

            @Override
            public void close() {
            }
        };
        WorkerLoop loop = loop(
                preparation,
                payload -> payload,
                new FakeNetworkFactory(),
                policy(1)
        );

        loop.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        loop.close();

        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        assertEquals(
                WorkerLifecycle.State.STOPPED,
                loop.snapshot().state()
        );
        assertFalse(executions.controlExecutor().isShutdown());
    }

    @Test
    void stopWaitsForCommandAndOldExitCannotStopTheNextRun()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakePreparation preparation = new FakePreparation(0);
        FakeNetworkFactory networks = new FakeNetworkFactory();
        WorkerLoop loop = loop(
                preparation,
                payload -> {
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return payload;
                },
                networks,
                policy(1)
        );
        loop.start();
        FakeTextMessageClient first = networks.awaitClient(0);
        first.open();
        await(loop::isConnected);
        first.message(CODEC.encodeWorkerCommand(command(
                "95992d31-9a9b-44b0-bd0a-1cfa18bb4402"
        )));
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        loop.stop();
        assertEquals(WorkerLifecycle.State.RUNNING, loop.snapshot().state());
        loop.start();
        assertEquals(1, preparation.calls.get());

        release.countDown();
        await(() -> loop.snapshot().state()
                == WorkerLifecycle.State.STOPPED);
        assertEquals(1, first.sent.size());

        loop.start();
        FakeTextMessageClient second = networks.awaitClient(1);
        first.terminateEndpoint();
        second.open();
        await(loop::isConnected);

        assertEquals(WorkerLifecycle.State.RUNNING, loop.snapshot().state());
        assertEquals(2, preparation.calls.get());
    }

    private WorkerLoop loop(
            WorkerPreparation preparation,
            WorkerEventHandler<String> handler,
            FakeNetworkFactory networks,
            WorkerRetryPolicy retryPolicy
    ) {
        WorkerEventDefinition<String> taskDefinition =
                WorkerEventDefinition.of(
                        "TASK",
                        "test.echo",
                        WorkerEventParameterResolvers.string(),
                        handler
                );
        WorkerEventDefinition<String> systemDefinition =
                WorkerEventDefinition.of(
                        "SYSTEM",
                        "test.system",
                        WorkerEventParameterResolvers.string(),
                        handler
                );
        WorkerLoop loop = new WorkerLoop(
                preparation,
                new WorkerCommandDispatcher(
                        List.of(taskDefinition, systemDefinition)
                ),
                networks,
                retryPolicy,
                executions.resources()
        );
        loops.add(loop);
        return loop;
    }

    private static WorkerRetryPolicy policy(int maxPrepareAttempts) {
        return WorkerRetryPolicy.of(
                maxPrepareAttempts,
                Duration.ofMillis(1),
                TextMessageReconnectPolicy.of(
                        1,
                        Duration.ofMillis(1),
                        Duration.ofMillis(1)
                )
        );
    }

    private static WorkerCommand command(String messageId) {
        return new WorkerCommand(
                messageId,
                WorkerMessageEndpoint.TASK,
                WorkerMessageEndpoint.WORKER,
                "test.echo",
                System.currentTimeMillis() + 60_000,
                "{\"value\":\"hello\"}",
                "forward"
        );
    }

    private static WorkerCommand systemCommand(String messageId) {
        return new WorkerCommand(
                messageId,
                WorkerMessageEndpoint.SYSTEM,
                WorkerMessageEndpoint.WORKER,
                "test.system",
                System.currentTimeMillis() + 60_000,
                "{\"value\":\"local\"}",
                "local-context"
        );
    }

    private static void await(CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(condition.evaluate(), "condition was not satisfied");
    }

    @FunctionalInterface
    private interface CheckedCondition {

        boolean evaluate() throws Exception;
    }

    private static final class FakePreparation implements WorkerPreparation {

        private final AtomicInteger calls = new AtomicInteger();
        private final int failures;

        private FakePreparation(int failures) {
            this.failures = failures;
        }

        @Override
        public PreparedWorker prepare() throws Exception {
            int call = calls.incrementAndGet();
            if (call <= failures) {
                throw new IOException("control unavailable");
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
        public PreparedWorker prepare() throws Exception {
            int call = calls.incrementAndGet();
            if (call == 1) {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return new PreparedWorker(WORKER_ID, ENDPOINT);
        }

        @Override
        public void close() {
            release.countDown();
        }
    }

    private static final class FakeNetworkFactory
            implements WorkerLoop.NetworkClientFactory {

        private final List<FakeTextMessageClient> clients =
                new CopyOnWriteArrayList<>();

        @Override
        public TextMessageClient create(URI endpointUri) {
            FakeTextMessageClient client = new FakeTextMessageClient();
            clients.add(client);
            return client;
        }

        private FakeTextMessageClient awaitClient(int index)
                throws Exception {
            await(() -> clients.size() > index);
            FakeTextMessageClient client = clients.get(index);
            await(() -> client.listener != null);
            return client;
        }
    }

    private static final class FakeTextMessageClient
            implements TextMessageClient {

        private final CountDownLatch startEntered;
        private final CountDownLatch startRelease;
        private final CountDownLatch closeEntered;
        private final CountDownLatch closeRelease;
        private volatile Listener listener;
        private volatile boolean connected;
        private final List<String> sent = new CopyOnWriteArrayList<>();

        private FakeTextMessageClient() {
            this(null, null, null, null);
        }

        private FakeTextMessageClient(
                CountDownLatch startEntered,
                CountDownLatch startRelease
        ) {
            this(startEntered, startRelease, null, null);
        }

        private FakeTextMessageClient(
                CountDownLatch startEntered,
                CountDownLatch startRelease,
                CountDownLatch closeEntered,
                CountDownLatch closeRelease
        ) {
            this.startEntered = startEntered;
            this.startRelease = startRelease;
            this.closeEntered = closeEntered;
            this.closeRelease = closeRelease;
        }

        @Override
        public void start(Listener listener) {
            this.listener = listener;
            if (startEntered == null) {
                return;
            }
            startEntered.countDown();
            try {
                if (!startRelease.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Timed out waiting to start client"
                    );
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while starting client",
                        error
                );
            }
        }

        @Override
        public boolean send(String message) {
            if (!connected) {
                return false;
            }
            sent.add(message);
            return true;
        }

        @Override
        public void closeCurrent(CloseReason reason) {
            connected = false;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public void close() {
            connected = false;
            if (closeEntered == null) {
                return;
            }
            closeEntered.countDown();
            try {
                if (!closeRelease.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Timed out waiting to close client"
                    );
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while closing client",
                        error
                );
            }
        }

        private void open() {
            connected = true;
            listener.onOpen();
        }

        private void message(String encodedCommand) {
            listener.onMessage(encodedCommand);
        }

        private void disconnect() {
            connected = false;
        }

        private void terminateEndpoint() {
            connected = false;
            listener.onEndpointTerminated();
        }
    }
}
