package com.xa.mass.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;

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
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";
    private static final WorkerDeliveryCodec CODEC =
            new WorkerDeliveryCodec();
    private final List<TextMessageWorkerTransport> runtimes =
            new ArrayList<>();
    private final List<ExecutorService> ownedExecutors =
            new ArrayList<>();
    private final TestCommandExecutor commands =
            new TestCommandExecutor();

    @AfterEach
    void tearDown() {
        for (TextMessageWorkerTransport runtime : runtimes) {
            runtime.close();
        }
        for (ExecutorService executor : ownedExecutors) {
            executor.shutdownNow();
        }
        commands.close();
    }

    @Test
    void openSendsOnlyConnectionBind() {
        FakeTextMessageClient client = new FakeTextMessageClient();
        TextMessageWorkerTransport runtime = runtime(
                client,
                command -> Optional.empty(),
                new RecordingListener()
        );

        runtime.start();
        client.open();

        assertEquals(1, client.sent.size());
        assertEquals(
                WORKER_ID,
                CODEC.decodeWorkerConnectionBind(client.sent.get(0))
                        .workerId()
        );
    }

    @Test
    void inboundJsonIsDecodedBeforeCommandExecution()
            throws Exception {
        FakeTextMessageClient client = new FakeTextMessageClient();
        AtomicReference<WorkerCommand> received = new AtomicReference<>();
        TextMessageWorkerTransport runtime = runtime(
                client,
                command -> {
                    received.set(command);
                    return Optional.empty();
                },
                new RecordingListener()
        );
        WorkerCommand command = command();

        runtime.start();
        client.open();
        client.message(CODEC.encodeWorkerCommand(command));

        await(() -> command.equals(received.get()));
        assertTrue(client.closeReasons.isEmpty());
    }

    @Test
    void commandBeforeBindIsRejectedWithoutExecution() {
        FakeTextMessageClient client = new FakeTextMessageClient();
        AtomicInteger executions = new AtomicInteger();
        TextMessageWorkerTransport runtime = runtime(
                client,
                command -> {
                    executions.incrementAndGet();
                    return Optional.empty();
                },
                new RecordingListener()
        );

        runtime.start();
        client.message(CODEC.encodeWorkerCommand(command()));

        assertEquals(0, executions.get());
        assertEquals(
                List.of(TextMessageClient.CloseReason.PROTOCOL_ERROR),
                client.closeReasons
        );
    }

    @Test
    void malformedCommandClosesCurrentConnection() {
        FakeTextMessageClient malformedClient = new FakeTextMessageClient();
        TextMessageWorkerTransport malformed = runtime(
                malformedClient,
                command -> Optional.empty(),
                new RecordingListener()
        );
        malformed.start();
        malformedClient.open();
        malformedClient.message("{}");

        assertEquals(
                List.of(TextMessageClient.CloseReason.PROTOCOL_ERROR),
                malformedClient.closeReasons
        );
    }

    @Test
    void distinctCommandsExecuteConcurrentlyAndCompleteOutOfOrder()
            throws Exception {
        String firstId = "95992d31-9a9b-44b0-bd0a-1cfa18bb4402";
        String secondId = "e2333b84-3455-48af-a17a-6d99115be544";
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch firstRelease = new CountDownLatch(1);
        CountDownLatch secondRelease = new CountDownLatch(1);
        FakeTextMessageClient client = new FakeTextMessageClient();
        ExecutorService concurrentExecutor = Executors.newFixedThreadPool(2);
        ownedExecutors.add(concurrentExecutor);
        TextMessageWorkerTransport transport = runtime(
                client,
                command -> {
                    entered.countDown();
                    awaitLatch(command.messageId().equals(firstId)
                            ? firstRelease
                            : secondRelease);
                    return Optional.of(result(command));
                },
                new RecordingListener(),
                concurrentExecutor
        );
        transport.start();
        client.open();

        client.message(CODEC.encodeWorkerCommand(command(firstId)));
        client.message(CODEC.encodeWorkerCommand(command(secondId)));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        secondRelease.countDown();
        await(() -> client.sent.size() == 2);
        firstRelease.countDown();
        await(() -> client.sent.size() == 3);

        assertEquals(
                secondId,
                CODEC.decodeWorkerResult(client.sent.get(1)).messageId()
        );
        assertEquals(
                firstId,
                CODEC.decodeWorkerResult(client.sent.get(2)).messageId()
        );
        assertTrue(client.closeReasons.isEmpty());
    }

    @Test
    void resultSendFailureDropsResultBeforeReconnect() throws Exception {
        FakeTextMessageClient client = new FakeTextMessageClient();
        TextMessageWorkerTransport runtime = runtime(
                client,
                command -> Optional.of(result()),
                new RecordingListener()
        );
        runtime.start();
        client.open();
        client.acceptSend = false;

        client.message(CODEC.encodeWorkerCommand(command()));
        await(() -> !client.closeReasons.isEmpty());
        client.acceptSend = true;
        client.open();

        assertEquals(
                TextMessageClient.CloseReason.SEND_FAILURE,
                client.closeReasons.get(0)
        );
        assertEquals(2, client.sent.size());
        assertEquals(
                WORKER_ID,
                CODEC.decodeWorkerConnectionBind(client.sent.get(1))
                        .workerId()
        );
    }

    @Test
    void resultCompletingWhileDisconnectedIsNotReplayed()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeTextMessageClient client = new FakeTextMessageClient();
        TextMessageWorkerTransport runtime = runtime(
                client,
                command -> {
                    entered.countDown();
                    awaitLatch(release);
                    return Optional.of(result());
                },
                new RecordingListener()
        );
        runtime.start();
        client.open();
        client.message(CODEC.encodeWorkerCommand(command()));
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        client.disconnect();
        release.countDown();
        assertTrue(client.disconnectedObserved.await(5, TimeUnit.SECONDS));
        client.open();

        assertEquals(2, client.sent.size());
        assertEquals(
                WORKER_ID,
                CODEC.decodeWorkerConnectionBind(client.sent.get(1))
                        .workerId()
        );
    }

    @Test
    void resultFromPreviousConnectionIsDroppedAfterReconnect()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeTextMessageClient client = new FakeTextMessageClient();
        TextMessageWorkerTransport runtime = runtime(
                client,
                command -> {
                    entered.countDown();
                    awaitLatch(release);
                    return Optional.of(result());
                },
                new RecordingListener()
        );
        runtime.start();
        client.open();
        client.message(CODEC.encodeWorkerCommand(command()));
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        client.disconnect();
        client.open();
        release.countDown();
        await(() -> client.sent.size() == 2);
        Thread.sleep(50);

        assertEquals(2, client.sent.size());
        assertEquals(
                WORKER_ID,
                CODEC.decodeWorkerConnectionBind(client.sent.get(1))
                        .workerId()
        );
    }

    @Test
    void endpointTerminationNotifiesExitExactlyOnce() {
        FakeTextMessageClient client = new FakeTextMessageClient();
        RecordingListener listener = new RecordingListener();
        TextMessageWorkerTransport runtime = runtime(
                client,
                command -> Optional.empty(),
                listener
        );
        runtime.start();

        client.terminate();
        client.terminate();

        assertEquals(1, listener.exits.get());
        assertSame(runtime, listener.lastRuntime.get());
    }

    @Test
    void endpointTerminationWaitsForCommandAndDropsResult()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeTextMessageClient client = new FakeTextMessageClient();
        RecordingListener listener = new RecordingListener();
        TextMessageWorkerTransport runtime = runtime(
                client,
                command -> {
                    entered.countDown();
                    awaitLatch(release);
                    return Optional.of(result());
                },
                listener
        );
        runtime.start();
        client.open();

        client.message(CODEC.encodeWorkerCommand(command()));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        client.terminate();
        assertEquals(0, listener.exits.get());

        release.countDown();
        await(() -> listener.exits.get() == 1);

        assertEquals(1, client.sent.size());
    }

    @Test
    void stopWaitsForCommandAndDropsResult() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeTextMessageClient client = new FakeTextMessageClient();
        RecordingListener listener = new RecordingListener();
        TextMessageWorkerTransport runtime = runtime(
                client,
                command -> {
                    entered.countDown();
                    awaitLatch(release);
                    return Optional.of(result());
                },
                listener
        );
        runtime.start();
        client.open();
        client.message(CODEC.encodeWorkerCommand(command()));
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        runtime.requestStop();
        assertEquals(0, listener.exits.get());
        assertEquals(1, client.closeCalls.get());

        release.countDown();
        await(() -> listener.exits.get() == 1);

        assertEquals(1, client.sent.size());
    }

    @Test
    void terminalRuntimeIgnoresLateMessages() {
        FakeTextMessageClient client = new FakeTextMessageClient();
        AtomicInteger executions = new AtomicInteger();
        RecordingListener listener = new RecordingListener();
        TextMessageWorkerTransport runtime = runtime(
                client,
                command -> {
                    executions.incrementAndGet();
                    return Optional.empty();
                },
                listener
        );
        runtime.start();
        client.open();

        runtime.requestStop();
        client.message("{}");
        client.message(CODEC.encodeWorkerCommand(command()));

        assertEquals(1, listener.exits.get());
        assertEquals(0, executions.get());
        assertTrue(client.closeReasons.isEmpty());
    }

    @Test
    void commandExecutorRejectionReturns1500WithoutTermination()
            throws Exception {
        ExecutorService rejectingExecutor =
                Executors.newSingleThreadExecutor();
        rejectingExecutor.shutdown();
        FakeTextMessageClient client = new FakeTextMessageClient();
        RecordingListener listener = new RecordingListener();
        TextMessageWorkerTransport runtime = runtime(
                client,
                command -> Optional.empty(),
                listener,
                rejectingExecutor
        );
        runtime.start();
        client.open();

        client.message(CODEC.encodeWorkerCommand(command()));

        await(() -> client.sent.size() == 2);
        WorkerResult rejected = CODEC.decodeWorkerResult(client.sent.get(1));
        assertEquals("1500", rejected.outcomeCode());
        assertEquals(command().messageId(), rejected.messageId());
        assertEquals(0, listener.exits.get());
        assertEquals(0, client.closeCalls.get());
    }

    @Test
    void uncaughtCommandExecutorFailureReturns1500()
            throws Exception {
        IllegalStateException failure = new IllegalStateException("boom");
        FakeTextMessageClient client = new FakeTextMessageClient();
        RecordingListener listener = new RecordingListener();
        TextMessageWorkerTransport runtime = runtime(
                client,
                command -> {
                    throw failure;
                },
                listener
        );
        runtime.start();
        client.open();

        client.message(CODEC.encodeWorkerCommand(command()));

        await(() -> client.sent.size() == 2);
        assertEquals(
                "1500",
                CODEC.decodeWorkerResult(client.sent.get(1)).outcomeCode()
        );
        assertEquals(0, listener.exits.get());
        assertEquals(0, client.closeCalls.get());
    }

    @Test
    void commandErrorCompletesCurrentCommandBeforeEscapingHandlerThread()
            throws Exception {
        AssertionError failure = new AssertionError("fatal command");
        CountDownLatch uncaught = new CountDownLatch(1);
        AtomicReference<Throwable> escaped = new AtomicReference<>();
        ExecutorService handler = Executors.newSingleThreadExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setUncaughtExceptionHandler((ignored, error) -> {
                        escaped.set(error);
                        uncaught.countDown();
                    });
                    return thread;
                }
        );
        FakeTextMessageClient client = new FakeTextMessageClient();
        RecordingListener listener = new RecordingListener();
        TextMessageWorkerTransport runtime = runtime(
                client,
                command -> {
                    throw failure;
                },
                listener,
                handler
        );
        try {
            runtime.start();
            client.open();
            client.message(CODEC.encodeWorkerCommand(command()));

            await(() -> client.sent.size() == 2);
            assertTrue(uncaught.await(5, TimeUnit.SECONDS));
            assertEquals(0, listener.exits.get());
            assertSame(failure, escaped.get());
        } finally {
            handler.shutdownNow();
        }
    }

    @Test
    void blockingResultSendDoesNotHoldRuntimeStateLock()
            throws Exception {
        FakeTextMessageClient client = new FakeTextMessageClient();
        client.blockedSendCall = 2;
        RecordingListener listener = new RecordingListener();
        TextMessageWorkerTransport runtime = runtime(
                client,
                command -> Optional.of(result()),
                listener
        );
        ExecutorService stopCaller = Executors.newSingleThreadExecutor();

        try {
            runtime.start();
            client.open();
            client.message(CODEC.encodeWorkerCommand(command()));
            assertTrue(client.sendEntered.await(5, TimeUnit.SECONDS));

            Future<?> stop = stopCaller.submit(runtime::requestStop);
            stop.get(5, TimeUnit.SECONDS);
            assertEquals(0, listener.exits.get());

            client.sendRelease.countDown();
            await(() -> listener.exits.get() == 1);
            assertEquals(1, client.sent.size());
        } finally {
            client.sendRelease.countDown();
            stopCaller.shutdownNow();
        }
    }

    @Test
    void closeLetsRunningHandlerFinishWithoutClosingSharedPool()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        FakeTextMessageClient client = new FakeTextMessageClient();
        TextMessageWorkerTransport runtime = runtime(
                client,
                command -> {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException error) {
                        interrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                    completed.countDown();
                    return Optional.empty();
                },
                new RecordingListener()
        );
        runtime.start();
        client.open();
        client.message(CODEC.encodeWorkerCommand(command()));
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        runtime.close();

        assertFalse(interrupted.await(100, TimeUnit.MILLISECONDS));
        release.countDown();
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertFalse(commands.executor().isShutdown());
    }

    private TextMessageWorkerTransport runtime(
            FakeTextMessageClient client,
            WorkerCommandExecutor commandExecutor,
            RecordingListener listener
    ) {
        return runtime(
                client,
                commandExecutor,
                listener,
                commands.executor()
        );
    }

    private TextMessageWorkerTransport runtime(
            FakeTextMessageClient client,
            WorkerCommandExecutor commandDispatcher,
            RecordingListener listener,
            ExecutorService commandExecutor
    ) {
        TextMessageWorkerTransport runtime = new TextMessageWorkerTransport(
                client,
                WORKER_ID,
                commandDispatcher,
                commandExecutor,
                listener
        );
        runtimes.add(runtime);
        return runtime;
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

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting", error);
        }
    }

    @FunctionalInterface
    private interface CheckedCondition {

        boolean evaluate() throws Exception;
    }

    private static WorkerCommand command() {
        return command("95992d31-9a9b-44b0-bd0a-1cfa18bb4402");
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

    private static WorkerResult result() {
        return result(command());
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

    private static final class RecordingListener
            implements TextMessageWorkerTransport.Listener {

        private final AtomicInteger exits = new AtomicInteger();
        private final AtomicReference<TextMessageWorkerTransport> lastRuntime =
                new AtomicReference<>();
        private final AtomicReference<Throwable> lastFailure =
                new AtomicReference<>();

        @Override
        public void onTerminated(
                TextMessageWorkerTransport runtime,
                Throwable failure
        ) {
            lastRuntime.set(runtime);
            lastFailure.set(failure);
            exits.incrementAndGet();
        }
    }

    private static final class FakeTextMessageClient
            implements TextMessageClient {

        private volatile Listener listener;
        private volatile boolean connected;
        private volatile boolean acceptSend = true;
        private volatile int blockedSendCall = -1;
        private final CountDownLatch disconnectedObserved =
                new CountDownLatch(1);
        private final CountDownLatch sendEntered = new CountDownLatch(1);
        private final CountDownLatch sendRelease = new CountDownLatch(1);
        private final AtomicInteger sendCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final List<String> sent = new CopyOnWriteArrayList<>();
        private final List<CloseReason> closeReasons =
                new CopyOnWriteArrayList<>();

        @Override
        public void start(Listener listener) {
            this.listener = listener;
        }

        @Override
        public boolean send(String message) {
            int sendCall = sendCalls.incrementAndGet();
            if (sendCall == blockedSendCall) {
                sendEntered.countDown();
                awaitLatch(sendRelease);
            }
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
            listener.onMessage(message);
        }

        private void disconnect() {
            connected = false;
            disconnectedObserved.countDown();
        }

        private void terminate() {
            connected = false;
            listener.onEndpointTerminated();
        }
    }
}
