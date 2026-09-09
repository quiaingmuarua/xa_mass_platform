package com.xa.mass.worker.transport.polling;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.worker.execution.WorkerCommandDispatcher;
import com.xa.mass.worker.execution.WorkerOutcomeReporter;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
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
    void retainedObservationDoesNotOccupyOrRetryThePendingExecutionResult() throws Exception {
        FakePointClient client = new FakePointClient();
        AtomicReference<WorkerOutcomeReporter> retained = new AtomicReference<>();
        var definition = WorkerEventDefinition.extension("tracked", WorkerEventParameterResolvers.string(),
                (payload, reporter) -> { retained.set(reporter); return "sent"; });
        client.commands.add(Optional.of(CODEC.encodeDeliveryCommand(DeliveryCommand.create(TASK,
                DeliveryEndpoint.WORKER, definition.eventName(), Long.MAX_VALUE, "input", "forward"))));
        var transport = transport(client, WorkerCommandDispatcher.forWorker(List.of(definition)));
        try {
            client.submitFailures = 1;
            assertThrows(IOException.class, transport::runOnce);
            assertTrue(transport.hasPendingResult());
            client.submitFailures = 1;
            assertFalse(retained.get().report(8, 1000, null));
            assertTrue(transport.hasPendingResult());
            assertTrue(retained.get().report(9, 1001, "reply"));
            assertTrue(transport.runOnce());
            assertFalse(transport.hasPendingResult());
            assertEquals(1, client.pollCount);
            assertEquals(2, client.submittedResults.size());
            assertEquals("platform.worker.task-outcome.observed",
                    CODEC.decodeDeliveryReport(client.submittedResults.get(0)).messageType());
            assertEquals("platform.worker.command.succeeded",
                    CODEC.decodeDeliveryReport(client.submittedResults.get(1)).messageType());
        } finally {
            transport.close();
        }
        assertFalse(retained.get().report(9, 1002, "after close"));
    }

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
                        "platform.worker.command.succeeded",
                        "",
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
                DeliveryEndpoint.SERVER,
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

    @Test
    void pendingFailureKeepsItsEventAndDoesNotExecuteTheCommandAgain() throws Exception {
        FakePointClient client = new FakePointClient();
        client.commands.add(Optional.of(CODEC.encodeDeliveryCommand(COMMAND)));
        client.submitFailures = 1;
        java.util.concurrent.atomic.AtomicInteger executions = new java.util.concurrent.atomic.AtomicInteger();
        try (PollingWorkerTransport transport = transport(client, command -> {
            executions.incrementAndGet();
            return Optional.of(WorkerCommandOutcome.failed(
                    com.xa.mass.worker.error.WorkerErrorCode.EVENT_EXECUTION_FAILED, "opaque-failure"));
        })) {
            assertThrows(IOException.class, transport::runOnce);
            assertTrue(transport.hasPendingResult());
            assertTrue(transport.runOnce());
            assertEquals(1, executions.get());
            DeliveryReport report = CODEC.decodeDeliveryReport(client.submittedResults.get(0));
            assertEquals("platform.worker.command.failed", report.messageType());
            assertEquals(COMMAND.forward(), report.forward());
            assertEquals("opaque-failure", report.payload());
        }
    }

    private static PollingWorkerTransport transport(
            WorkerPointClient client,
            WorkerCommandExecutor executor
    ) {
        return new PollingWorkerTransport(client, WORKER_ID, executor);
    }

    private static WorkerCommandOutcome outcome() {
        return WorkerCommandOutcome.succeeded("{\"observed\":\"input\"}");
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
