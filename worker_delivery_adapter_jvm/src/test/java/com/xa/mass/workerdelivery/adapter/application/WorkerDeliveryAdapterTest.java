package com.xa.mass.workerdelivery.adapter.application;

import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt.DELIVERED;
import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt.UNKNOWN;
import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.WorkerConnectionCloseReason.ADAPTER_STOPPING;
import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.WorkerConnectionCloseReason.RESULT_BUFFER_FULL;
import static com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter.WorkerResultAcceptance.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter.WorkerResultAcceptance.BUFFER_FULL;
import static com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter.WorkerResultAcceptance.INVALID_OUTCOME;
import static com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter.WorkerResultAcceptance.STALE_SESSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt;
import com.xa.mass.workerdelivery.adapter.application.WorkerConnection.WorkerConnectionCloseReason;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter.AdapterRoundResult;
import com.xa.mass.workerdelivery.adapter.application.WorkerSessionDirectory.WorkerSessionToken;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerDeliveryAdapterTest {

    private static final String COMMAND_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";

    @Test
    void deliversConnectedWorkerAndRejectsOnlyKnownMissingSession() {
        FakeGateway gateway = new FakeGateway();
        InMemoryWorkerSessionDirectory sessions =
                new InMemoryWorkerSessionDirectory();
        WorkerDeliveryAdapter adapter = adapter(gateway, sessions, 10);
        FakeConnection connected = new FakeConnection(DELIVERED);
        FakeConnection unknown = new FakeConnection(UNKNOWN);
        adapter.connectWorker("worker-1", connected);
        adapter.connectWorker("worker-3", unknown);
        Map<String, WorkerCommandEnvelope> commands =
                new LinkedHashMap<>();
        commands.put("worker-1", command(COMMAND_ID, "worker-1", 2_000));
        commands.put(
                "worker-2",
                command(
                        "9f0d983c-8010-4d59-a6d2-e8fedb8d0059",
                        "worker-2",
                        2_000
                )
        );
        commands.put(
                "worker-3",
                command(
                        "66f60ac8-e68f-4783-90e3-13b20a54ca13",
                        "worker-3",
                        2_000
                )
        );
        gateway.pages.add(new WorkerCommandPage(commands, "7"));

        AdapterRoundResult result = adapter.dispatchOnce();

        assertThat(result).isEqualTo(new AdapterRoundResult(
                3,
                1,
                1,
                1,
                0,
                0
        ));
        assertThat(gateway.appendedResults).containsExactly(List.of(
                new SeedResult(
                        "9f0d983c-8010-4d59-a6d2-e8fedb8d0059",
                        "context",
                        "3001",
                        null
                )
        ));
    }

    @Test
    void dropsExpiredCommandAndAdvancesCursor() {
        FakeGateway gateway = new FakeGateway();
        InMemoryWorkerSessionDirectory sessions =
                new InMemoryWorkerSessionDirectory();
        WorkerDeliveryAdapter adapter = adapter(gateway, sessions, 10);
        gateway.pages.add(new WorkerCommandPage(
                Map.of(
                        "worker-1",
                        command(COMMAND_ID, "worker-1", 1_000)
                ),
                "7"
        ));
        gateway.pages.add(new WorkerCommandPage(Map.of(), null));

        AdapterRoundResult first = adapter.dispatchOnce();
        adapter.dispatchOnce();

        assertThat(first.expiredCount()).isEqualTo(1);
        assertThat(gateway.requestedCursors)
                .containsExactly(null, "7");
        assertThat(gateway.appendedResults).isEmpty();
    }

    @Test
    void staleInvalidAndOverflowResultsHaveExplicitOutcomes() {
        FakeGateway gateway = new FakeGateway();
        InMemoryWorkerSessionDirectory sessions =
                new InMemoryWorkerSessionDirectory();
        WorkerDeliveryAdapter adapter = adapter(gateway, sessions, 2);
        FakeConnection oldConnection = new FakeConnection(DELIVERED);
        FakeConnection currentConnection = new FakeConnection(DELIVERED);
        WorkerSessionToken oldToken =
                adapter.connectWorker("worker-1", oldConnection);
        WorkerSessionToken currentToken =
                adapter.connectWorker("worker-1", currentConnection);

        assertThat(adapter.acceptWorkerResult(
                oldToken,
                result(COMMAND_ID, "200")
        )).isEqualTo(STALE_SESSION);
        assertThat(adapter.acceptWorkerResult(
                currentToken,
                result(COMMAND_ID, "3001")
        )).isEqualTo(INVALID_OUTCOME);
        assertThat(adapter.acceptWorkerResult(
                currentToken,
                result(COMMAND_ID, "200")
        )).isEqualTo(ACCEPTED);
        assertThat(adapter.acceptWorkerResult(
                currentToken,
                result(
                        "9f0d983c-8010-4d59-a6d2-e8fedb8d0059",
                        "1500"
                )
        )).isEqualTo(ACCEPTED);
        assertThat(adapter.acceptWorkerResult(
                currentToken,
                result(
                        "66f60ac8-e68f-4783-90e3-13b20a54ca13",
                        "1500"
                )
        )).isEqualTo(BUFFER_FULL);
        assertThat(currentConnection.closedReasons)
                .containsExactly(RESULT_BUFFER_FULL);
    }

    @Test
    void retriesPendingResultsBeforeConsumingMoreCommands() {
        FakeGateway gateway = new FakeGateway();
        InMemoryWorkerSessionDirectory sessions =
                new InMemoryWorkerSessionDirectory();
        WorkerDeliveryAdapter adapter = adapter(gateway, sessions, 10);
        WorkerSessionToken token = adapter.connectWorker(
                "worker-1",
                new FakeConnection(DELIVERED)
        );
        SeedResult result = result(COMMAND_ID, "200");
        assertThat(adapter.acceptWorkerResult(token, result))
                .isEqualTo(ACCEPTED);
        gateway.appendFailures = 1;
        gateway.pages.add(new WorkerCommandPage(Map.of(), null));

        assertThatThrownBy(adapter::dispatchOnce)
                .isInstanceOf(WorkerDeliveryAdapterException.class);
        assertThat(gateway.consumeCount).isZero();

        adapter.dispatchOnce();

        assertThat(gateway.appendAttempts).isEqualTo(2);
        assertThat(gateway.appendedResults)
                .containsExactly(List.of(result));
        assertThat(gateway.consumeCount).isEqualTo(1);
    }

    @Test
    void closeFlushesResultsAndClosesCurrentSessions() {
        FakeGateway gateway = new FakeGateway();
        InMemoryWorkerSessionDirectory sessions =
                new InMemoryWorkerSessionDirectory();
        WorkerDeliveryAdapter adapter = adapter(gateway, sessions, 10);
        FakeConnection connection = new FakeConnection(DELIVERED);
        WorkerSessionToken token =
                adapter.connectWorker("worker-1", connection);
        SeedResult result = result(COMMAND_ID, "200");
        assertThat(adapter.acceptWorkerResult(token, result))
                .isEqualTo(ACCEPTED);

        adapter.close();

        assertThat(gateway.appendedResults)
                .containsExactly(List.of(result));
        assertThat(connection.closedReasons)
                .containsExactly(ADAPTER_STOPPING);
    }

    @Test
    void configRequiresAnAdapterEndpointAndPositiveBounds() {
        assertThatThrownBy(() -> new WorkerDeliveryAdapter.Config(
                "system-polling",
                100,
                100,
                1000
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerDeliveryAdapter.Config(
                "adapter-1",
                0,
                100,
                1000
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static WorkerDeliveryAdapter adapter(
            FakeGateway gateway,
            InMemoryWorkerSessionDirectory sessions,
            int resultBufferCapacity
    ) {
        return new WorkerDeliveryAdapter(
                gateway,
                new WorkerDeliveryCodec(),
                sessions,
                new WorkerDeliveryAdapter.Config(
                        "websocket-adapter-1",
                        100,
                        100,
                        resultBufferCapacity
                ),
                () -> 1_000L
        );
    }

    private static WorkerCommandEnvelope command(
            String commandId,
            String workerId,
            long deadline
    ) {
        return new WorkerCommandEnvelope(
                commandId,
                WorkerMessageType.TASK_ITEM,
                deadline,
                "{\"opaqueDeliveryItem\":\"item\","
                        + "\"opaqueResultContext\":\"context\","
                        + "\"workerId\":\"" + workerId + "\"}"
        );
    }

    private static SeedResult result(
            String commandId,
            String outcomeCode
    ) {
        return new SeedResult(
                commandId,
                "context",
                outcomeCode,
                "200".equals(outcomeCode) ? "null" : null
        );
    }

    private static final class FakeConnection
            implements WorkerConnection {
        private final CommandDeliveryAttempt attempt;
        private final List<WorkerConnectionCloseReason> closedReasons =
                new ArrayList<>();

        private FakeConnection(CommandDeliveryAttempt attempt) {
            this.attempt = attempt;
        }

        @Override
        public CommandDeliveryAttempt deliver(
                WorkerCommandEnvelope command
        ) {
            return attempt;
        }

        @Override
        public void close(WorkerConnectionCloseReason reason) {
            closedReasons.add(reason);
        }
    }

    private static final class FakeGateway
            implements WorkerDeliveryGatewayClient {
        private final ArrayDeque<WorkerCommandPage> pages =
                new ArrayDeque<>();
        private final List<String> requestedCursors =
                new ArrayList<>();
        private final List<List<SeedResult>> appendedResults =
                new ArrayList<>();
        private int appendFailures;
        private int appendAttempts;
        private int consumeCount;

        @Override
        public WorkerCommandPage consumeWorkerCommands(
                String endpointManagerId,
                String cursor,
                int scanCount
        ) {
            consumeCount++;
            requestedCursors.add(cursor);
            return pages.removeFirst();
        }

        @Override
        public void appendResults(
                String endpointManagerId,
                List<SeedResult> results
        ) {
            appendAttempts++;
            if (appendFailures > 0) {
                appendFailures--;
                throw new WorkerDeliveryAdapterException(
                        "gateway unavailable"
                );
            }
            appendedResults.add(List.copyOf(results));
        }
    }
}
