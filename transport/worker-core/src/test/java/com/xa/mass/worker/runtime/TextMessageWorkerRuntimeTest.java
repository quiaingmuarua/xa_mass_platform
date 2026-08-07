package com.xa.mass.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class TextMessageWorkerRuntimeTest {

    private static final String WORKER_ID =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";
    private static final WorkerDeliveryCodec CODEC =
            new WorkerDeliveryCodec();

    @Test
    void openSendsConnectionBindBeforePendingResult() {
        FakeTextMessageClient client = new FakeTextMessageClient();
        WorkerResultSlot slot = new WorkerResultSlot();
        WorkerResult result = result();
        assertTrue(slot.offer(result));
        TextMessageWorkerRuntime runtime = runtime(
                client,
                command -> true,
                slot,
                new RecordingListener()
        );

        runtime.start();
        client.open();

        assertEquals(2, client.sent.size());
        assertEquals(
                WORKER_ID,
                CODEC.decodeWorkerConnectionBind(client.sent.get(0))
                        .workerId()
        );
        assertEquals(result, CODEC.decodeWorkerResult(client.sent.get(1)));
        assertFalse(slot.hasResult());
        assertTrue(runtime.isConnected());
    }

    @Test
    void inboundJsonIsDecodedBeforeTheUnifiedCommandReceiver() {
        FakeTextMessageClient client = new FakeTextMessageClient();
        AtomicReference<WorkerCommand> received = new AtomicReference<>();
        TextMessageWorkerRuntime runtime = runtime(
                client,
                command -> {
                    received.set(command);
                    return true;
                },
                new WorkerResultSlot(),
                new RecordingListener()
        );
        WorkerCommand command = command();

        runtime.start();
        client.open();
        client.message(CODEC.encodeWorkerCommand(command));

        assertEquals(command, received.get());
        assertTrue(client.closeReasons.isEmpty());
    }

    @Test
    void rejectedOrMalformedCommandClosesCurrentConnection() {
        FakeTextMessageClient rejectedClient = new FakeTextMessageClient();
        TextMessageWorkerRuntime rejected = runtime(
                rejectedClient,
                command -> false,
                new WorkerResultSlot(),
                new RecordingListener()
        );
        rejected.start();
        rejectedClient.open();
        rejectedClient.message(CODEC.encodeWorkerCommand(command()));

        FakeTextMessageClient malformedClient = new FakeTextMessageClient();
        TextMessageWorkerRuntime malformed = runtime(
                malformedClient,
                command -> true,
                new WorkerResultSlot(),
                new RecordingListener()
        );
        malformed.start();
        malformedClient.open();
        malformedClient.message("{}");

        assertEquals(
                List.of(TextMessageClient.CloseReason.PROTOCOL_ERROR),
                rejectedClient.closeReasons
        );
        assertEquals(
                List.of(TextMessageClient.CloseReason.PROTOCOL_ERROR),
                malformedClient.closeReasons
        );
    }

    @Test
    void resultSendFailureKeepsSlotForTheNextConnection() {
        FakeTextMessageClient client = new FakeTextMessageClient();
        WorkerResultSlot slot = new WorkerResultSlot();
        TextMessageWorkerRuntime runtime = runtime(
                client,
                command -> true,
                slot,
                new RecordingListener()
        );
        runtime.start();
        client.open();
        WorkerResult result = result();
        assertTrue(slot.offer(result));
        client.acceptSend = false;

        runtime.flushPendingResult();

        assertTrue(slot.hasResult());
        assertEquals(
                TextMessageClient.CloseReason.SEND_FAILURE,
                client.closeReasons.get(0)
        );
    }

    @Test
    void reconnectExhaustionNotifiesExitExactlyOnce() {
        FakeTextMessageClient client = new FakeTextMessageClient();
        RecordingListener listener = new RecordingListener();
        TextMessageWorkerRuntime runtime = runtime(
                client,
                command -> true,
                new WorkerResultSlot(),
                listener
        );
        runtime.start();

        client.exhaust();
        client.exhaust();

        assertEquals(1, listener.exits.get());
        assertSame(runtime, listener.lastRuntime.get());
        assertFalse(runtime.isConnected());
    }

    private static TextMessageWorkerRuntime runtime(
            FakeTextMessageClient client,
            TextMessageWorkerRuntime.CommandReceiver receiver,
            WorkerResultSlot slot,
            RecordingListener listener
    ) {
        return new TextMessageWorkerRuntime(
                client,
                WORKER_ID,
                receiver,
                slot,
                listener
        );
    }

    private static WorkerCommand command() {
        return new WorkerCommand(
                "95992d31-9a9b-44b0-bd0a-1cfa18bb4402",
                WorkerMessageEndpoint.TASK,
                WorkerMessageEndpoint.WORKER,
                "test.echo",
                System.currentTimeMillis() + 60_000,
                "{\"value\":\"hello\"}",
                "forward"
        );
    }

    private static WorkerResult result() {
        return new WorkerResult(
                "95992d31-9a9b-44b0-bd0a-1cfa18bb4402",
                WorkerMessageEndpoint.TASK,
                "test.echo",
                "200",
                "{\"value\":\"hello\"}",
                "forward"
        );
    }

    private static final class RecordingListener
            implements TextMessageWorkerRuntime.Listener {

        private final AtomicInteger exits = new AtomicInteger();
        private final AtomicReference<TextMessageWorkerRuntime> lastRuntime =
                new AtomicReference<>();

        @Override
        public void onStateChanged(
                TextMessageWorkerRuntime runtime,
                Throwable failure
        ) {
            lastRuntime.set(runtime);
        }

        @Override
        public void onExit(TextMessageWorkerRuntime runtime) {
            lastRuntime.set(runtime);
            exits.incrementAndGet();
        }
    }

    private static final class FakeTextMessageClient
            implements TextMessageClient {

        private Listener listener;
        private boolean connected;
        private boolean acceptSend = true;
        private final List<String> sent = new ArrayList<>();
        private final List<CloseReason> closeReasons = new ArrayList<>();

        @Override
        public void start(Listener listener) {
            this.listener = listener;
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

        private void message(String message) {
            listener.onMessage(message);
        }

        private void exhaust() {
            connected = false;
            listener.onReconnectExhausted();
        }
    }
}
