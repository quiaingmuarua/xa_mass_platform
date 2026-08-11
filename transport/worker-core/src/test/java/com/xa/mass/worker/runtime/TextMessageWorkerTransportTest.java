package com.xa.mass.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
                    return Optional.of(result(command));
                },
                new RecordingListener()
        );

        transport.start();
        client.deliver(CODEC.encodeWorkerCommand(command("before-bind")));
        client.open();
        client.message(CODEC.encodeWorkerCommand(command("after-bind")));

        assertEquals(2, executions.get());
        assertTrue(client.closeReasons.isEmpty());
        assertIdentity(client.sent.get(0));
        assertEquals(id("after-bind"), CODEC.decodeWorkerResult(
                client.sent.get(1)
        ).messageId());
    }

    @Test
    void everyPhysicalOpenSendsFreshIdentityResult() {
        FakeTextMessageClient client = new FakeTextMessageClient();
        TextMessageWorkerTransport transport = transport(
                client,
                command -> Optional.empty(),
                new RecordingListener()
        );
        transport.start();

        client.open();
        client.open();

        WorkerResult first = identity(client.sent.get(0));
        WorkerResult second = identity(client.sent.get(1));
        assertNotEquals(first.messageId(), second.messageId());
        assertEquals(WORKER_ID, first.payload());
        assertEquals(WORKER_ID, second.payload());
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
                    executed.add(command.messageId());
                    return Optional.of(result(command));
                },
                new RecordingListener()
        );
        transport.start();
        client.open();

        client.message(CODEC.encodeWorkerCommand(command("one")));
        client.message(CODEC.encodeWorkerCommand(command("two")));

        assertSame(callbackThread, executionThread.get());
        assertEquals(List.of(id("one"), id("two")), executed);
        assertEquals(id("one"), CODEC.decodeWorkerResult(
                client.sent.get(1)
        ).messageId());
        assertEquals(id("two"), CODEC.decodeWorkerResult(
                client.sent.get(2)
        ).messageId());
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

        client.message(CODEC.encodeWorkerCommand(command("expired")));

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
                    return Optional.of(result(command));
                },
                new RecordingListener()
        );
        transport.start();
        client.open();

        client.message("{}");
        client.message(CODEC.encodeWorkerCommand(command("valid")));

        assertEquals(1, executions.get());
        assertTrue(client.closeReasons.isEmpty());
        assertEquals(2, client.sent.size());
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
                    return Optional.of(result(command));
                },
                listener
        );
        transport.start();
        client.open();

        client.message(CODEC.encodeWorkerCommand(command("failed")));
        client.message(CODEC.encodeWorkerCommand(command("recovered")));

        assertEquals(2, client.sent.size());
        assertEquals(id("recovered"), CODEC.decodeWorkerResult(
                client.sent.get(1)
        ).messageId());
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
                () -> client.message(CODEC.encodeWorkerCommand(
                        command("fatal")
                ))
        );

        assertSame(failure, thrown);
        assertEquals(1, client.sent.size());
        assertTrue(client.closeReasons.isEmpty());
        transport.requestStop();
    }

    @Test
    void failedResultSendIsDroppedWithoutClosingCurrentConnection() {
        FakeTextMessageClient client = new FakeTextMessageClient();
        TextMessageWorkerTransport transport = transport(
                client,
                command -> Optional.of(result(command)),
                new RecordingListener()
        );
        transport.start();
        client.open();
        client.acceptSend = false;

        client.message(CODEC.encodeWorkerCommand(command("drop")));

        assertEquals(1, client.sent.size());
        assertTrue(client.closeReasons.isEmpty());

        client.acceptSend = true;
        client.message(CODEC.encodeWorkerCommand(command("next")));
        assertEquals(2, client.sent.size());
        assertEquals(id("next"), CODEC.decodeWorkerResult(
                client.sent.get(1)
        ).messageId());
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
                    return Optional.of(result(command));
                },
                listener
        );
        transport.start();
        client.open();

        client.message(CODEC.encodeWorkerCommand(closeCommand("null")));

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

        client.message(CODEC.encodeWorkerCommand(closeCommand("{}")));

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

        client.message(CODEC.encodeWorkerCommand(new WorkerCommand(
                id("expired-adapter-close"),
                WorkerMessageEndpoint.ADAPTER,
                WorkerMessageEndpoint.WORKER,
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
                    return Optional.of(result(command));
                },
                listener
        );
        transport.start();
        client.open();

        client.message(CODEC.encodeWorkerCommand(new WorkerCommand(
                id("task-close"),
                WorkerMessageEndpoint.TASK,
                WorkerMessageEndpoint.WORKER,
                "worker.connection.close",
                System.currentTimeMillis() + 60_000,
                "null",
                "forward"
        )));
        client.message(CODEC.encodeWorkerCommand(new WorkerCommand(
                id("adapter-inspect"),
                WorkerMessageEndpoint.ADAPTER,
                WorkerMessageEndpoint.WORKER,
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
    void stopDropsResultFromAlreadyStartedHandler() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeTextMessageClient client = new FakeTextMessageClient();
        RecordingListener listener = new RecordingListener();
        TextMessageWorkerTransport transport = transport(
                client,
                command -> {
                    entered.countDown();
                    awaitLatch(release);
                    return Optional.of(result(command));
                },
                listener
        );
        transport.start();
        client.open();
        ExecutorService callback = Executors.newSingleThreadExecutor();
        try {
            Future<?> handling = callback.submit(() -> client.message(
                    CODEC.encodeWorkerCommand(command("slow"))
            ));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            transport.requestStop();
            assertEquals(1, listener.terminations.get());
            assertEquals(1, client.closeCalls.get());

            release.countDown();
            handling.get(5, TimeUnit.SECONDS);
            assertEquals(1, listener.terminations.get());
            assertEquals(1, client.sent.size());
        } finally {
            release.countDown();
            callback.shutdownNow();
        }
    }

    @Test
    void endpointTerminationNotifiesExactlyOnceWhileHandlerFinishes()
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
                    return Optional.of(result(command));
                },
                listener
        );
        transport.start();
        client.open();
        ExecutorService callback = Executors.newSingleThreadExecutor();
        try {
            Future<?> handling = callback.submit(() -> client.message(
                    CODEC.encodeWorkerCommand(command("slow"))
            ));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            client.terminate();
            client.terminate();
            assertEquals(1, listener.terminations.get());

            release.countDown();
            handling.get(5, TimeUnit.SECONDS);
            assertEquals(1, listener.terminations.get());
            assertSame(transport, listener.lastTransport.get());
            assertEquals(1, client.sent.size());
        } finally {
            release.countDown();
            callback.shutdownNow();
        }
    }

    @Test
    void terminalTransportRejectsLateMessages() {
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
        client.message(CODEC.encodeWorkerCommand(command("late")));

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
                        listener
                );
        transports.add(transport);
        return transport;
    }

    private static WorkerCommand command(String messageId) {
        return new WorkerCommand(
                id(messageId),
                WorkerMessageEndpoint.TASK,
                WorkerMessageEndpoint.WORKER,
                "test.echo",
                System.currentTimeMillis() + 60_000,
                "{\"value\":\"hello\"}",
                "forward"
        );
    }

    private static WorkerCommand closeCommand(String payload) {
        return new WorkerCommand(
                id("adapter-close"),
                WorkerMessageEndpoint.ADAPTER,
                WorkerMessageEndpoint.WORKER,
                "worker.connection.close",
                System.currentTimeMillis() + 60_000,
                payload,
                ""
        );
    }

    private static String id(String value) {
        return UUID.nameUUIDFromBytes(
                value.getBytes(StandardCharsets.UTF_8)
        ).toString();
    }

    private static WorkerResult result(WorkerCommand command) {
        return new WorkerResult(
                command.messageId(),
                command.src(),
                command.messageType(),
                "200",
                "{\"value\":\"hello\"}",
                command.forward()
        );
    }

    private static void assertIdentity(String encoded) {
        identity(encoded);
    }

    private static WorkerResult identity(String encoded) {
        WorkerResult result = CODEC.decodeWorkerResult(encoded);
        UUID.fromString(result.messageId());
        assertEquals(WorkerMessageEndpoint.ADAPTER, result.dst());
        assertEquals(
                "worker.connection.identify",
                result.messageType()
        );
        assertEquals("200", result.outcomeCode());
        assertEquals(WORKER_ID, result.payload());
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
