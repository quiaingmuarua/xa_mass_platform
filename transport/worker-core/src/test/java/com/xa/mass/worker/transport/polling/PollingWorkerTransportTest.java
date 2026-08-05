package com.xa.mass.worker.transport.polling;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.TASK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.transport.client.WorkerPointClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PollingWorkerTransportTest {

    private static final String COMMAND = "{\"command\":\"opaque\"}";
    private static final String COMMAND_ID =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";

    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    @Test
    void executesEncodedCommandAndSubmitsEncodedResult()
            throws Exception {
        FakePointClient client = new FakePointClient();
        client.commands.add(Optional.of(COMMAND));
        AtomicReference<String> executed = new AtomicReference<>();
        PollingWorkerTransport transport = transport(
                client,
                encoded -> {
                    executed.set(encoded);
                    return Optional.of(result());
                }
        );

        assertTrue(transport.runOnce());

        assertEquals(COMMAND, executed.get());
        assertEquals(1, client.pollCount);
        assertEquals(1, client.submittedResults.size());
        assertEquals(
                result(),
                codec.decodeWorkerResult(
                        client.submittedResults.get(0)
                )
        );
        assertFalse(transport.hasPendingResult());
        transport.close();
    }

    @Test
    void emptyPollAndDroppedCommandAreBoundedNoOps()
            throws Exception {
        FakePointClient client = new FakePointClient();
        client.commands.add(Optional.empty());
        client.commands.add(Optional.of(COMMAND));
        PollingWorkerTransport transport = transport(
                client,
                encoded -> Optional.empty()
        );

        assertFalse(transport.runOnce());
        assertFalse(transport.runOnce());

        assertEquals(2, client.pollCount);
        assertTrue(client.submittedResults.isEmpty());
        transport.close();
    }

    @Test
    void pendingResultRetriesBeforePollingAnotherCommand()
            throws Exception {
        FakePointClient client = new FakePointClient();
        client.commands.add(Optional.of(COMMAND));
        client.submitFailures = 1;
        PollingWorkerTransport transport = transport(
                client,
                encoded -> Optional.of(result())
        );

        assertThrows(IOException.class, transport::runOnce);
        assertTrue(transport.hasPendingResult());
        assertEquals(1, client.pollCount);

        assertTrue(transport.runOnce());
        assertFalse(transport.hasPendingResult());
        assertEquals(1, client.pollCount);
        assertEquals(1, client.submittedResults.size());
        transport.close();
    }

    @Test
    void closeOwnsThePointClientAndPreventsNewRounds() {
        FakePointClient client = new FakePointClient();
        PollingWorkerTransport transport = transport(
                client,
                encoded -> Optional.empty()
        );

        transport.close();
        transport.close();

        assertTrue(client.closed);
        assertThrows(IllegalStateException.class, transport::runOnce);
    }

    private static PollingWorkerTransport transport(
            WorkerPointClient client,
            WorkerCommandExecutor executor
    ) {
        return new PollingWorkerTransport(client, COMMAND_ID, executor);
    }

    private static WorkerResult result() {
        return new WorkerResult(
                COMMAND_ID,
                TASK,
                "test.observe",
                "200",
                "{\"observed\":\"input\"}",
                "opaque-context"
        );
    }

    private static final class FakePointClient
            implements WorkerPointClient {

        private final ArrayDeque<Optional<String>> commands =
                new ArrayDeque<>();
        private final List<String> submittedResults =
                new ArrayList<>();
        private int pollCount;
        private int submitFailures;
        private boolean closed;

        @Override
        public Optional<String> pollCommand(String workerId) {
            assertEquals(COMMAND_ID, workerId);
            pollCount++;
            Optional<String> command = commands.poll();
            return command == null ? Optional.empty() : command;
        }

        @Override
        public void submitResult(String workerId, String encodedResult)
                throws IOException {
            assertEquals(COMMAND_ID, workerId);
            if (submitFailures > 0) {
                submitFailures--;
                throw new IOException("scripted result failure");
            }
            submittedResults.add(encodedResult);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
