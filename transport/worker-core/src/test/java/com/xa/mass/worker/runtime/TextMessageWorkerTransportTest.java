package com.xa.mass.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.worker.execution.WorkerCommandOutcome;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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

class TextMessageWorkerTransportTest {

    private static final String WORKER_ID =
            "server-issued-worker-id";
    private static final WorkerDeliveryCodec CODEC =
            new WorkerDeliveryCodec();

    private final List<TextMessageWorkerTransport> transports =
            new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (TextMessageWorkerTransport transport : transports) {
            transport.close();
        }
    }

    @Test
    void commandHandlingDoesNotDependOnLocalBindState() {
        FakeTextMessageClient client = new FakeTextMessageClient();
        AtomicInteger executions = new AtomicInteger();
        TextMessageWorkerTransport transport = transport(
                client,
                command -> {
                    executions.incrementAndGet();
                    return Optional.of(outcome(command));
                },
                new RecordingListener()
        );

        transport.start();
        DeliveryCommand beforeBind = command("before-bind");
        DeliveryCommand afterBind = command("after-bind");
        client.deliver(CODEC.encodeDeliveryCommand(beforeBind));
        client.open();
        client.message(CODEC.encodeDeliveryCommand(afterBind));

        assertEquals(2, executions.get());
        assertTrue(client.closeReasons.isEmpty());
        assertIdentity(client.sent.get(0));
        assertEquals(afterBind.payload(), CODEC.decodeDeliveryReport(
                client.sent.get(1)
        ).payload());
    }

    @Test
    void everyPhysicalOpenSendsIdentityResult() {
        FakeTextMessageClient client = new FakeTextMessageClient();
        TextMessageWorkerTransport transport = transport(
                client,
                command -> Optional.empty(),
                new RecordingListener()
        );
        transport.start();

        client.open();
        client.open();

        DeliveryReport first = identity(client.sent.get(0));
        DeliveryReport second = identity(client.sent.get(1));
        assertEquals(first, second);
        assertEquals(WORKER_ID, first.sourceId());
        assertEquals(WORKER_ID, second.sourceId());
    }

    @Test
    void rejectsBlankWorkerIdentity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TextMessageWorkerTransport(
                        new FakeTextMessageClient(),
                        " ",
                        command -> Optional.empty(),
                        java.util.Map::of,
                        new RecordingListener()
                )
        );
    }

    @Test
    void rejectedIdentitySendClosesCurrentConnectionForReconnect() {
        FakeTextMessageClient client = new FakeTextMessageClient();
        TextMessageWorkerTransport transport = transport(
                client,
                command -> Optional.empty(),
                new RecordingListener()
        );
        transport.start();
        client.acceptSend = false;

        client.open();

        assertEquals(
                List.of(TextMessageClient.CloseReason.SEND_FAILURE),
                client.closeReasons
        );
    }

    @Test
    void dispatcherRunsSynchronouslyOnClientCallbackThreadInOrder() {
        FakeTextMessageClient client = new FakeTextMessageClient();
        Thread callbackThread = Thread.currentThread();
        List<String> executed = new ArrayList<>();
        AtomicReference<Thread> executionThread = new AtomicReference<>();
        TextMessageWorkerTransport transport = transport(
                client,
                command -> {
                    executionThread.set(Thread.currentThread());
                    executed.add(command.payload());
                    return Optional.of(outcome(command));
                },
                new RecordingListener()
        );
        transport.start();
        client.open();

        DeliveryCommand one = command("one");
        DeliveryCommand two = command("two");
        client.message(CODEC.encodeDeliveryCommand(one));
        client.message(CODEC.encodeDeliveryCommand(two));

        assertSame(callbackThread, executionThread.get());
        assertEquals(List.of(one.payload(), two.payload()), executed);
        assertEquals(one.payload(), CODEC.decodeDeliveryReport(
                client.sent.get(1)
        ).payload());
        assertEquals(two.payload(), CODEC.decodeDeliveryReport(
                client.sent.get(2)
        ).payload());
    }

    @Test
    void emptyDispatcherResultSendsNothing() {
        FakeTextMessageClient client = new FakeTextMessageClient();
        TextMessageWorkerTransport transport = transport(
                client,
                command -> Optional.empty(),
                new RecordingListener()
        );
        transport.start();
        client.open();

        client.message(CODEC.encodeDeliveryCommand(command("expired")));

        assertEquals(1, client.sent.size());
    }

    @Test
    void malformedCommandIsDroppedWithoutClosingConnection() {
        FakeTextMessageClient client = new FakeTextMessageClient();
        AtomicInteger executions = new AtomicInteger();
        TextMessageWorkerTransport transport = transport(
                client,
                command -> {
                    executions.incrementAndGet();
                    return Optional.of(outcome(command));
                },
                new RecordingListener()
        );
        transport.start();
        client.open();

        client.message("{}");
        client.message(CODEC.encodeDeliveryCommand(command("valid")));

        assertEquals(1, executions.get());
        assertTrue(client.closeReasons.isEmpty());
        assertEquals(2, client.sent.size());
    }

    @Test
    void commandForAnotherDestinationIsDroppedBeforeDispatch() {
        FakeTextMessageClient client = new FakeTextMessageClient();
        AtomicInteger executions = new AtomicInteger();
        TextMessageWorkerTransport transport = transport(
                client,
                command -> {
                    executions.incrementAndGet();
                    return Optional.empty();
                },
                new RecordingListener()
        );
        transport.start();
        client.open();

        client.message(CODEC.encodeDeliveryCommand(
                DeliveryCommand.create(
                        DeliveryEndpoint.SYSTEM,
                        DeliveryEndpoint.TASK,
                        "system.observe",
                        System.currentTimeMillis() + 60_000,
                        "null",
                        ""
                )
        ));

        assertEquals(0, executions.get());
        assertTrue(client.closeReasons.isEmpty());
        assertEquals(1, client.sent.size());
    }

    @Test
    void dispatcherFailureIsDroppedWithoutEndingRun() {
        FakeTextMessageClient client = new FakeTextMessageClient();
        RecordingListener listener = new RecordingListener();
        AtomicInteger calls = new AtomicInteger();
        TextMessageWorkerTransport transport = transport(
                client,
                command -> {
                    if (calls.getAndIncrement() == 0) {
                        throw new IllegalStateException("boom");
                    }
                    return Optional.of(outcome(command));
                },
                listener
        );
        transport.start();
        client.open();

        DeliveryCommand failed = command("failed");
        DeliveryCommand recovered = command("recovered");
        client.message(CODEC.encodeDeliveryCommand(failed));
        client.message(CODEC.encodeDeliveryCommand(recovered));

        assertEquals(2, client.sent.size());
        assertEquals(recovered.payload(), CODEC.decodeDeliveryReport(
                client.sent.get(1)
        ).payload());
        assertTrue(client.closeReasons.isEmpty());
        assertEquals(0, listener.terminations.get());
    }

    @Test
    void dispatcherErrorPropagatesWithoutFallbackResult() {
        FakeTextMessageClient client = new FakeTextMessageClient();
        AssertionError failure = new AssertionError("fatal");
        TextMessageWorkerTransport transport = transport(
                client,
                command -> {
                    throw failure;
                },
                new RecordingListener()
        );
        transport.start();
        client.open();

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> client.message(CODEC.encodeDeliveryCommand(
                        command("fatal")
                ))
        );

        assertSame(failure, thrown);
        assertEquals(1, client.sent.size());
        assertTrue(client.closeReasons.isEmpty());
        transport.close();
    }

    @Test
    void failedResultSendIsDroppedWithoutClosingCurrentConnection() {
        FakeTextMessageClient client = new FakeTextMessageClient();
        TextMessageWorkerTransport transport = transport(
                client,
                command -> Optional.of(outcome(command)),
                new RecordingListener()
        );
        transport.start();
        client.open();
        client.acceptSend = false;

        DeliveryCommand drop = command("drop");
        client.message(CODEC.encodeDeliveryCommand(drop));

        assertEquals(1, client.sent.size());
        assertTrue(client.closeReasons.isEmpty());

        client.acceptSend = true;
        DeliveryCommand next = command("next");
        client.message(CODEC.encodeDeliveryCommand(next));
        assertEquals(2, client.sent.size());
        assertEquals(next.payload(), CODEC.decodeDeliveryReport(
                client.sent.get(1)
        ).payload());
    }

    @Test
    void adapterCloseTerminatesWithoutDispatchOrResult() {
        FakeTextMessageClient client = new FakeTextMessageClient();
        RecordingListener listener = new RecordingListener();
        AtomicInteger executions = new AtomicInteger();
        TextMessageWorkerTransport transport = transport(
                client,
                command -> {
                    executions.incrementAndGet();
                    return Optional.of(outcome(command));
                },
                listener
        );
        transport.start();
        client.open();

        client.message(CODEC.encodeDeliveryCommand(closeCommand("null")));

        assertEquals(0, executions.get());
        assertEquals(1, client.sent.size());
        assertEquals(1, client.closeCalls.get());
        assertEquals(1, listener.terminations.get());
    }

    @Test
    void adapterClosePayloadDoesNotEnterBusinessDispatcher() {
        FakeTextMessageClient client = new FakeTextMessageClient();
        RecordingListener listener = new RecordingListener();
        TextMessageWorkerTransport transport = transport(
                client,
                command -> {
                    throw new AssertionError(
                            "Connection close must not enter dispatcher"
                    );
                },
                listener
        );
        transport.start();
        client.open();

        client.message(CODEC.encodeDeliveryCommand(closeCommand("{}")));

        assertEquals(1, client.sent.size());
        assertEquals(1, client.closeCalls.get());
        assertEquals(1, listener.terminations.get());
    }

    @Test
    void expiredAdapterCloseIsDroppedWithoutDispatch() {
        FakeTextMessageClient client = new FakeTextMessageClient();
        RecordingListener listener = new RecordingListener();
        TextMessageWorkerTransport transport = transport(
                client,
                command -> {
                    throw new AssertionError(
                            "Connection close must not enter dispatcher"
                    );
                },
                listener
        );
        transport.start();
        client.open();

        client.message(CODEC.encodeDeliveryCommand(DeliveryCommand.create(
                DeliveryEndpoint.ADAPTER,
                DeliveryEndpoint.WORKER,
                "worker.connection.close",
                1L,
                "null",
                ""
        )));

        assertEquals(1, client.sent.size());
        assertEquals(0, client.closeCalls.get());
        assertEquals(0, listener.terminations.get());
    }

    @Test
    void sameEventFromTaskAndOtherAdapterEventsAreDispatchedNormally() {
        FakeTextMessageClient client = new FakeTextMessageClient();
        RecordingListener listener = new RecordingListener();
        AtomicInteger executions = new AtomicInteger();
        TextMessageWorkerTransport transport = transport(
                client,
                command -> {
                    executions.incrementAndGet();
                    return Optional.of(outcome(command));
                },
                listener
        );
        transport.start();
        client.open();

        client.message(CODEC.encodeDeliveryCommand(DeliveryCommand.create(
                DeliveryEndpoint.TASK,
                DeliveryEndpoint.WORKER,
                "worker.connection.close",
                System.currentTimeMillis() + 60_000,
                "null",
                "forward"
        )));
        client.message(CODEC.encodeDeliveryCommand(DeliveryCommand.create(
                DeliveryEndpoint.ADAPTER,
                DeliveryEndpoint.WORKER,
                "adapter.inspect",
                System.currentTimeMillis() + 60_000,
                "null",
                ""
        )));

        assertEquals(2, executions.get());
        assertEquals(3, client.sent.size());
        assertEquals(0, client.closeCalls.get());
        assertEquals(0, listener.terminations.get());
    }

    @Test
    void closeDropsResultFromAlreadyStartedHandler() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeTextMessageClient client = new FakeTextMessageClient();
        RecordingListener listener = new RecordingListener();
        TextMessageWorkerTransport transport = transport(
                client,
                command -> {
                    entered.countDown();
                    awaitLatch(release);
                    return Optional.of(outcome(command));
                },
                listener
        );
        transport.start();
        client.open();
        ExecutorService callback = Executors.newSingleThreadExecutor();
        try {
            Future<?> handling = callback.submit(() -> client.message(
                    CODEC.encodeDeliveryCommand(command("slow"))
            ));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            transport.close();
            assertEquals(0, listener.terminations.get());
            assertEquals(1, client.closeCalls.get());

            release.countDown();
            handling.get(5, TimeUnit.SECONDS);
            assertEquals(0, listener.terminations.get());
            assertEquals(1, client.sent.size());
        } finally {
            release.countDown();
            callback.shutdownNow();
        }
    }

    @Test
    void endpointTerminationDelegatesWhileHandlerFinishes()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeTextMessageClient client = new FakeTextMessageClient();
        RecordingListener listener = new RecordingListener();
        TextMessageWorkerTransport transport = transport(
                client,
                command -> {
                    entered.countDown();
                    awaitLatch(release);
                    return Optional.of(outcome(command));
                },
                listener
        );
        transport.start();
        client.open();
        ExecutorService callback = Executors.newSingleThreadExecutor();
        try {
            Future<?> handling = callback.submit(() -> client.message(
                    CODEC.encodeDeliveryCommand(command("slow"))
            ));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            client.terminate();
            client.terminate();
            assertEquals(2, listener.terminations.get());

            release.countDown();
            handling.get(5, TimeUnit.SECONDS);
            assertEquals(2, listener.terminations.get());
            assertSame(transport, listener.lastTransport.get());
            assertEquals(1, client.sent.size());
        } finally {
            release.countDown();
            callback.shutdownNow();
        }
    }

    @Test
    void clientSuppressesLateMessagesAfterEndpointTermination() {
        FakeTextMessageClient client = new FakeTextMessageClient();
        AtomicInteger executions = new AtomicInteger();
        RecordingListener listener = new RecordingListener();
        TextMessageWorkerTransport transport = transport(
                client,
                command -> {
                    executions.incrementAndGet();
                    return Optional.empty();
                },
                listener
        );
        transport.start();
        client.open();

        client.terminate();
        client.message(CODEC.encodeDeliveryCommand(command("late")));

        assertEquals(1, listener.terminations.get());
        assertEquals(0, executions.get());
    }

    private TextMessageWorkerTransport transport(
            FakeTextMessageClient client,
            WorkerCommandExecutor dispatcher,
            RecordingListener listener
    ) {
        TextMessageWorkerTransport transport =
                new TextMessageWorkerTransport(
                        client,
                        WORKER_ID,
                        dispatcher,
                        java.util.Map::of,
                        listener
                );
        transports.add(transport);
        return transport;
    }

    private static DeliveryCommand command(String marker) {
        return DeliveryCommand.create(
                DeliveryEndpoint.TASK,
                DeliveryEndpoint.WORKER,
                "test.echo",
                System.currentTimeMillis() + 60_000,
                "{\"value\":\"" + marker + "\"}",
                "forward"
        );
    }

    private static DeliveryCommand closeCommand(String payload) {
        return DeliveryCommand.create(
                DeliveryEndpoint.ADAPTER,
                DeliveryEndpoint.WORKER,
                "worker.connection.close",
                System.currentTimeMillis() + 60_000,
                payload,
                ""
        );
    }

    private static WorkerCommandOutcome outcome(DeliveryCommand command) {
        return WorkerCommandOutcome.of(
                "200",
                command.payload()
        );
    }

    private static void assertIdentity(String encoded) {
        identity(encoded);
    }

    private static DeliveryReport identity(String encoded) {
        DeliveryReport result = CODEC.decodeDeliveryReport(encoded);
        assertEquals(DeliveryEndpoint.WORKER, result.src());
        assertEquals(WORKER_ID, result.sourceId());
        assertEquals(DeliveryEndpoint.ADAPTER, result.dst());
        assertEquals(
                "worker.connection.identify",
                result.messageType()
        );
        assertEquals("200", result.outcomeCode());
        assertEquals("null", result.payload());
        assertEquals("", result.forward());
        return result;
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

    private static final class RecordingListener
            implements TextMessageWorkerTransport.Listener {

        private final AtomicInteger terminations = new AtomicInteger();
        private final AtomicReference<TextMessageWorkerTransport>
                lastTransport = new AtomicReference<>();

        @Override
        public void onTerminated(
                TextMessageWorkerTransport transport,
                Throwable failure
        ) {
            lastTransport.set(transport);
            terminations.incrementAndGet();
        }
    }

    private static final class FakeTextMessageClient
            implements TextMessageClient {

        private Listener listener;
        private boolean connected;
        private boolean acceptSend = true;
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final List<String> sent = new CopyOnWriteArrayList<>();
        private final List<CloseReason> closeReasons =
                new CopyOnWriteArrayList<>();

        @Override
        public void start(Listener value) {
            listener = value;
        }

        @Override
        public boolean send(String message) {
            if (!connected || !acceptSend) {
                return false;
            }
            sent.add(message);
            return true;
        }

        @Override
        public void closeCurrent(CloseReason reason) {
            closeReasons.add(reason);
            connected = false;
        }

        @Override
        public void close() {
            connected = false;
            closeCalls.incrementAndGet();
        }

        private void open() {
            connected = true;
            listener.onOpen();
        }

        private void message(String message) {
            if (connected) {
                listener.onMessage(message);
            }
        }

        private void deliver(String message) {
            listener.onMessage(message);
        }

        private void terminate() {
            connected = false;
            listener.onEndpointTerminated();
        }
    }
}
