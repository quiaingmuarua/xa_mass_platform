package com.xa.mass.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventHandler;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class WorkerLoopTest {

    private static final String WORKER_ID =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";
    private static final URI ENDPOINT = URI.create(
            "ws://127.0.0.1:18083/worker"
    );
    private static final WorkerDeliveryCodec CODEC =
            new WorkerDeliveryCodec();

    private final List<WorkerLoop> loops = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (WorkerLoop loop : loops) {
            loop.close();
        }
    }

    @Test
    void localAndRemoteCommandsUseTheSameDispatcherEntry() throws Exception {
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
        assertEquals(WorkerLifecycle.State.RUNNING, loop.snapshot().state());
        assertFalse(loop.send(command(
                "95992d31-9a9b-44b0-bd0a-1cfa18bb4402"
        )));
        client.open();
        await(loop::isConnected);

        assertTrue(loop.send(systemCommand(
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
    void busyWorkerRejectsAnotherCommandWithoutQueueingIt()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeNetworkFactory networks = new FakeNetworkFactory();
        WorkerLoop loop = loop(
                new FakePreparation(0),
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

        assertTrue(loop.send(command(
                "95992d31-9a9b-44b0-bd0a-1cfa18bb4402"
        )));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertFalse(loop.send(command(
                "6cae656c-2f1c-495d-abce-07a945c69b3d"
        )));
        release.countDown();
        await(() -> client.sent.size() == 2);

        assertEquals(
                "95992d31-9a9b-44b0-bd0a-1cfa18bb4402",
                CODEC.decodeWorkerResult(client.sent.get(1)).messageId()
        );
    }

    @Test
    void commandCompletesBeforeReprepareAndPendingResultCrossesRuntime()
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
        assertTrue(loop.send(command(
                "95992d31-9a9b-44b0-bd0a-1cfa18bb4402"
        )));
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        first.exhaust();
        await(() -> loop.snapshot().state()
                == WorkerLifecycle.State.PREPARING);
        assertEquals(1, preparation.calls.get());

        release.countDown();
        FakeTextMessageClient second = networks.awaitClient(1);
        assertEquals(2, preparation.calls.get());
        second.open();
        await(() -> second.sent.size() == 2);

        assertNotNull(CODEC.decodeWorkerConnectionBind(second.sent.get(0)));
        WorkerResult result = CODEC.decodeWorkerResult(second.sent.get(1));
        assertNotNull(result);
        assertEquals(
                "95992d31-9a9b-44b0-bd0a-1cfa18bb4402",
                result.messageId()
        );
    }

    @Test
    void reconnectExhaustionTriggersExactlyOneNewPreparation()
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

        first.exhaust();
        first.exhaust();
        networks.awaitClient(1);
        Thread.sleep(50);

        assertEquals(2, preparation.calls.get());
        assertEquals(2, networks.clients.size());
    }

    @Test
    void ordinaryReconnectStaysInsideCurrentRuntime() throws Exception {
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

        assertEquals(1, preparation.calls.get());
        assertEquals(1, networks.clients.size());
    }

    @Test
    void preparationRetriesAreBoundedAndErrorNeedsExplicitRestart()
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
        await(() -> loop.snapshot().state()
                == WorkerLifecycle.State.ERROR);
        assertEquals(2, preparation.calls.get());
        assertEquals(0, networks.clients.size());

        loop.start();
        networks.awaitClient(0);
        await(() -> loop.snapshot().state()
                == WorkerLifecycle.State.RUNNING);
        assertEquals(3, preparation.calls.get());
    }

    @Test
    void nonRetryablePreparationFailureEntersErrorImmediately()
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
        await(() -> loop.snapshot().state()
                == WorkerLifecycle.State.ERROR);

        assertEquals(1, calls.get());
    }

    @Test
    void stopInvalidatesScheduledPreparationRetry() throws Exception {
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
        Thread.sleep(250);

        assertEquals(1, preparation.calls.get());
        assertEquals(WorkerLifecycle.State.STOPPED, loop.snapshot().state());
    }

    @Test
    void stopPreventsLateRuntimeExitFromRestartingPreparation()
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
        FakeTextMessageClient client = networks.awaitClient(0);
        client.open();
        await(loop::isConnected);

        loop.stop();
        client.exhaust();
        Thread.sleep(50);

        assertEquals(1, preparation.calls.get());
        assertEquals(WorkerLifecycle.State.STOPPED, loop.snapshot().state());
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
                List.of(taskDefinition, systemDefinition),
                networks,
                retryPolicy
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

        private volatile Listener listener;
        private volatile boolean connected;
        private final List<String> sent = new CopyOnWriteArrayList<>();

        @Override
        public void start(Listener listener) {
            this.listener = listener;
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
            listener.onDisconnected();
        }

        private void exhaust() {
            connected = false;
            listener.onReconnectExhausted();
        }
    }
}
