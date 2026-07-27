package com.xa.mass.server.workerdelivery.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.server.workerdelivery.WorkerDeliveryException;
import com.xa.mass.server.workerdelivery.WorkerDeliveryService;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.server.workerdelivery.WorkerDeliveryRuntime.WorkerCommandPage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import com.xa.mass.server.workerdelivery.websocket.WorkerSessionRegistry.DeliveryAttempt;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerDeliveryPumpTest {

    private static final String COMMAND_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";
    private WorkerDeliveryService service;
    private WorkerSessionRegistry sessions;
    private WorkerDeliveryPump pump;

    @BeforeEach
    void setUp() {
        service = mock(WorkerDeliveryService.class);
        sessions = mock(WorkerSessionRegistry.class);
        pump = new WorkerDeliveryPump(
                service,
                new WorkerDeliveryCodec(),
                sessions,
                WorkerSessionRegistryTest.properties(2),
                () -> 1_000L
        );
    }

    @Test
    void sendsConnectedWorkersAndRejectsOnlyKnownMissingSessions() {
        WorkerCommandEnvelope delivered = command(
                COMMAND_ID,
                "worker-1",
                2_000
        );
        WorkerCommandEnvelope missing = command(
                "9f0d983c-8010-4d59-a6d2-e8fedb8d0059",
                "worker-2",
                2_000
        );
        WorkerCommandEnvelope unknown = command(
                "66f60ac8-e68f-4783-90e3-13b20a54ca13",
                "worker-3",
                2_000
        );
        when(service.consumeWorkerCommands(
                "websocket-adapter-1",
                null,
                100
        )).thenReturn(new WorkerCommandPage(
                Map.of(
                        "worker-1", delivered,
                        "worker-2", missing,
                        "worker-3", unknown
                ),
                "7"
        ));
        when(sessions.send(eq("worker-1"), anyString()))
                .thenReturn(DeliveryAttempt.DELIVERED);
        when(sessions.send(eq("worker-2"), anyString()))
                .thenReturn(DeliveryAttempt.REJECTED_BEFORE_SEND);
        when(sessions.send(eq("worker-3"), anyString()))
                .thenReturn(DeliveryAttempt.UNKNOWN);
        SeedResult rejection = new SeedResult(
                missing.commandId(),
                "context",
                "3001",
                null
        );
        when(service.createAdapterRejections(
                eq("websocket-adapter-1"),
                eq(Map.of("worker-2", missing)),
                eq("3001")
        )).thenReturn(List.of(rejection));
        when(service.appendAdapterResults(
                "websocket-adapter-1",
                List.of(rejection)
        )).thenReturn(1);

        pump.runOnce();

        verify(service).appendAdapterResults(
                "websocket-adapter-1",
                List.of(rejection)
        );
    }

    @Test
    void dropsExpiredCommandsWithoutSendingOrRejecting() {
        WorkerCommandEnvelope expired = command(
                COMMAND_ID,
                "worker-1",
                1_000
        );
        when(service.consumeWorkerCommands(
                "websocket-adapter-1",
                null,
                100
        )).thenReturn(new WorkerCommandPage(
                Map.of("worker-1", expired),
                null
        ));

        pump.runOnce();

        verify(sessions, never()).send(eq("worker-1"), anyString());
        verify(service, never()).createAdapterRejections(
                eq("websocket-adapter-1"),
                eq(Map.of("worker-1", expired)),
                anyString()
        );
    }

    @Test
    void advancesTheMailboxCursorBetweenRounds() {
        when(service.consumeWorkerCommands(
                "websocket-adapter-1",
                null,
                100
        )).thenReturn(new WorkerCommandPage(Map.of(), "7"));
        when(service.consumeWorkerCommands(
                "websocket-adapter-1",
                "7",
                100
        )).thenReturn(new WorkerCommandPage(Map.of(), null));

        pump.runOnce();
        pump.runOnce();

        verify(service).consumeWorkerCommands(
                "websocket-adapter-1",
                "7",
                100
        );
    }

    @Test
    void retainsFailedResultBatchAndRetriesBeforeConsumingCommands() {
        SeedResult result = new SeedResult(
                COMMAND_ID,
                "context",
                "200",
                "null"
        );
        assertThat(pump.acceptWorkerResult(result)).isTrue();
        when(service.appendWorkerResults(
                "websocket-adapter-1",
                List.of(result)
        ))
                .thenThrow(WorkerDeliveryException.unavailable(
                        new IllegalStateException("redis")
                ))
                .thenReturn(1);
        when(service.consumeWorkerCommands(
                "websocket-adapter-1",
                null,
                100
        )).thenReturn(new WorkerCommandPage(Map.of(), null));

        pump.runOnce();
        verify(service, never()).consumeWorkerCommands(
                "websocket-adapter-1",
                null,
                100
        );

        pump.runOnce();
        verify(service, times(2)).appendWorkerResults(
                "websocket-adapter-1",
                List.of(result)
        );
        verify(service).consumeWorkerCommands(
                "websocket-adapter-1",
                null,
                100
        );
    }

    @Test
    void resultBufferIsBounded() {
        SeedResult first = new SeedResult(
                COMMAND_ID,
                "context-1",
                "200",
                "null"
        );
        SeedResult second = new SeedResult(
                "9f0d983c-8010-4d59-a6d2-e8fedb8d0059",
                "context-2",
                "1500",
                null
        );
        SeedResult third = new SeedResult(
                "66f60ac8-e68f-4783-90e3-13b20a54ca13",
                "context-3",
                "1500",
                null
        );

        assertThat(pump.acceptWorkerResult(first)).isTrue();
        assertThat(pump.acceptWorkerResult(second)).isTrue();
        assertThat(pump.acceptWorkerResult(third)).isFalse();
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
}
