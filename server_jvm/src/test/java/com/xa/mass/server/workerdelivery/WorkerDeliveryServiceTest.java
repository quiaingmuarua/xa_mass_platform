package com.xa.mass.server.workerdelivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerDeliveryServiceTest {

    private static final String COMMAND_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";
    private WorkerDeliveryRuntime runtime;
    private WorkerDeliveryService service;

    @BeforeEach
    void setUp() {
        runtime = mock(WorkerDeliveryRuntime.class);
        service = new WorkerDeliveryService(
                runtime,
                new WorkerDeliveryCodec()
        );
    }

    @Test
    void pointPollDropsACommandThatExpiredAfterRedisConsumption() {
        when(runtime.consumeWorkerCommand("endpoint-1", "worker-1"))
                .thenReturn(new WorkerCommandEnvelope(
                        COMMAND_ID,
                        WorkerMessageType.TASK_ITEM,
                        System.currentTimeMillis() - 1,
                        "item"
                ));

        assertThat(service.pollWorkerCommand("endpoint-1", "worker-1"))
                .isNull();
    }

    @Test
    void workerResultRejectsAdapterEvidence() {
        SeedResult result = new SeedResult(
                COMMAND_ID,
                "context",
                "3001",
                null
        );

        assertThatThrownBy(() -> service.appendWorkerResult(
                "endpoint-1",
                "worker-1",
                result
        ))
                .isInstanceOf(WorkerDeliveryException.class)
                .extracting(error -> ((WorkerDeliveryException) error).kind())
                .isEqualTo(WorkerDeliveryException.Kind.INVALID);
        verify(runtime, never()).appendSeedResults(List.of(result));
    }

    @Test
    void adapterBatchAcceptsAllOutcomeFamiliesInOneCall() {
        List<SeedResult> results = List.of(
                new SeedResult(COMMAND_ID, "success", "200", "null"),
                new SeedResult(
                        "9f0d983c-8010-4d59-a6d2-e8fedb8d0059",
                        "failure",
                        "1500",
                        null
                ),
                new SeedResult(
                        "66f60ac8-e68f-4783-90e3-13b20a54ca13",
                        "rejection",
                        "3001",
                        null
                )
        );
        when(runtime.appendSeedResults(results)).thenReturn(results.size());

        assertThat(service.appendAdapterResults("endpoint-1", results))
                .isEqualTo(3);
        verify(runtime).appendSeedResults(results);
    }

    @Test
    void workerBatchRejectsAdapterEvidence() {
        SeedResult rejection = new SeedResult(
                COMMAND_ID,
                "context",
                "3001",
                null
        );

        assertThatThrownBy(() -> service.appendWorkerResults(
                "endpoint-1",
                List.of(rejection)
        )).isInstanceOf(WorkerDeliveryException.class);
        verify(runtime, never()).appendSeedResults(List.of(rejection));
    }

    @Test
    void createsTrustedAdapterRejectionsFromDeliverSeeds() {
        WorkerCommandEnvelope command = new WorkerCommandEnvelope(
                COMMAND_ID,
                WorkerMessageType.TASK_ITEM,
                999_999,
                """
                        {"opaqueDeliveryItem":"item",\
                        "opaqueResultContext":"context","workerId":"worker-1"}\
                        """
        );

        assertThat(service.createAdapterRejections(
                "endpoint-1",
                Map.of("worker-1", command),
                "3001"
        )).containsExactly(
                new SeedResult(COMMAND_ID, "context", "3001", null)
        );
    }

    @Test
    void skipsCorruptOrMismatchedAdapterRejectionCommands() {
        WorkerCommandEnvelope corrupt = new WorkerCommandEnvelope(
                COMMAND_ID,
                WorkerMessageType.TASK_ITEM,
                999_999,
                "not-json"
        );
        WorkerCommandEnvelope mismatch = new WorkerCommandEnvelope(
                "9f0d983c-8010-4d59-a6d2-e8fedb8d0059",
                WorkerMessageType.TASK_ITEM,
                999_999,
                """
                        {"opaqueDeliveryItem":"item",\
                        "opaqueResultContext":"context","workerId":"other"}\
                        """
        );

        assertThat(service.createAdapterRejections(
                "endpoint-1",
                Map.of("worker-1", corrupt, "worker-2", mismatch),
                "3001"
        )).isEmpty();
    }

    @Test
    void systemPollingCannotUseAdapterBatchOperations() {
        assertThatThrownBy(() -> service.appendAdapterResults(
                WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID,
                List.of(new SeedResult(
                        COMMAND_ID,
                        "context",
                        "200",
                        "null"
                ))
        )).isInstanceOf(WorkerDeliveryException.class);
    }
}
