package com.xa.mass.worker.transport.polling;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.worker.execution.WorkerCommandOutcome;
import com.xa.mass.transport.client.WorkerPointClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PollingWorkerTransportTest {

    private static final String WORKER_ID = "worker-1";

    private static final WorkerDeliveryCodec CODEC =
            new WorkerDeliveryCodec();
    private static final DeliveryCommand COMMAND = DeliveryCommand.create(
            TASK,
            DeliveryEndpoint.WORKER,
            "test.observe",
            Long.MAX_VALUE,
            "{\"value\":\"input\"}",
            "opaque-context"
    );
    private static final String ENCODED_COMMAND =
            CODEC.encodeDeliveryCommand(COMMAND);

    @Test
    void executesEncodedCommandAndSubmitsEncodedResult()
            throws Exception {
        FakePointClient client = new FakePointClient();
        client.commands.add(Optional.of(ENCODED_COMMAND));
        AtomicReference<DeliveryCommand> executed = new AtomicReference<>();
        PollingWorkerTransport transport = transport(
                client,
                encoded -> {
                    executed.set(encoded);
                    return Optional.of(outcome());
                }
        );

        assertTrue(transport.runOnce());

        assertEquals(COMMAND, executed.get());
        assertEquals(1, client.pollCount);
        assertEquals(1, client.submittedResults.size());
        assertEquals(
                DeliveryReport.fromCommand(
                        COMMAND,
                        DeliveryEndpoint.WORKER,
                        WORKER_ID,
                        "200",
                        "{\"observed\":\"input\"}"
                ),
                CODEC.decodeDeliveryReport(
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
        client.commands.add(Optional.of(ENCODED_COMMAND));
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
        client.commands.add(Optional.of(ENCODED_COMMAND));
        client.submitFailures = 1;
        PollingWorkerTransport transport = transport(
                client,
                encoded -> Optional.of(outcome())
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

    @Test
    void commandForAnotherDestinationIsRejectedAtPollingBoundary() {
        FakePointClient client = new FakePointClient();
        DeliveryCommand misrouted = DeliveryCommand.create(
                DeliveryEndpoint.SYSTEM,
                DeliveryEndpoint.TASK,
                "system.observe",
                Long.MAX_VALUE,
                "null",
                ""
        );
        client.commands.add(Optional.of(
                CODEC.encodeDeliveryCommand(misrouted)
        ));
        PollingWorkerTransport transport = transport(
                client,
                encoded -> Optional.empty()
        );

        assertThrows(
                com.xa.mass.worker.error.WorkerException.class,
                transport::runOnce
        );
        transport.close();
    }

    private static PollingWorkerTransport transport(
            WorkerPointClient client,
            WorkerCommandExecutor executor
    ) {
        return new PollingWorkerTransport(client, WORKER_ID, executor);
    }

    private static WorkerCommandOutcome outcome() {
        return WorkerCommandOutcome.of(
                "200",
                "{\"observed\":\"input\"}"
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
            assertEquals(WORKER_ID, workerId);
            pollCount++;
            Optional<String> command = commands.poll();
            return command == null ? Optional.empty() : command;
        }

        @Override
        public void submitResult(String workerId, String encodedResult)
                throws IOException {
            assertEquals(WORKER_ID, workerId);
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
