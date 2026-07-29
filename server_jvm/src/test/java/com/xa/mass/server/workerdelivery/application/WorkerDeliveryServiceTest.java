package com.xa.mass.server.workerdelivery.application;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultSource.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultSource.WORKER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.delivery.SeedResultRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerDeliveryServiceTest {

    private static final String COMMAND_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private WorkerCommandRuntime commandRuntime;
    private SeedResultRuntime resultRuntime;
    private WorkerDeliveryService service;

    @BeforeEach
    void setUp() {
        commandRuntime = mock(WorkerCommandRuntime.class);
        resultRuntime = mock(SeedResultRuntime.class);
        service = new WorkerDeliveryService(
                commandRuntime,
                resultRuntime
        );
    }

    @Test
    void pointPollDropsACommandThatExpiredAfterRedisConsumption() {
        when(commandRuntime.consumeWorkerCommand("endpoint-1", "worker-1"))
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
        SeedResult result = result(COMMAND_ID, "3001");

        assertThatThrownBy(() -> service.appendWorkerResult(
                "endpoint-1",
                "worker-1",
                result
        ))
                .isInstanceOf(ServerException.class)
                .extracting(
                        error -> ((ServerException) error).errorCode()
                )
                .isEqualTo(
                        ServerErrorCode.INVALID_WORKER_DELIVERY_REQUEST
                );
        verify(resultRuntime, never()).appendSeedResults(List.of(result));
    }

    @Test
    void workerSourceRejectsForgeryAndMalformedItemsButAppendsValidItems() {
        SeedResult success = result(COMMAND_ID, "200");
        SeedResult failure = result(
                "9f0d983c-8010-4d59-a6d2-e8fedb8d0059",
                "1500"
        );
        SeedResult forgedRejection = result(
                "66f60ac8-e68f-4783-90e3-13b20a54ca13",
                "3001"
        );
        List<SeedResult> accepted = List.of(success, failure);
        when(resultRuntime.appendSeedResults(accepted))
                .thenReturn(accepted.size());

        var counts = service.appendAdapterResults(
                "endpoint-1",
                WORKER,
                java.util.Arrays.asList(
                        codec.encodeSeedResult(success),
                        "not-json",
                        null,
                        codec.encodeSeedResult(failure),
                        codec.encodeSeedResult(forgedRejection)
                )
        );

        assertThat(counts.acceptedCount()).isEqualTo(2);
        assertThat(counts.rejectedCount()).isEqualTo(3);
        verify(resultRuntime).appendSeedResults(accepted);
    }

    @Test
    void adapterSourceAcceptsOnlyAdapterEvidence() {
        SeedResult success = result(COMMAND_ID, "200");
        SeedResult rejection = result(
                "9f0d983c-8010-4d59-a6d2-e8fedb8d0059",
                "3001"
        );
        when(resultRuntime.appendSeedResults(List.of(rejection)))
                .thenReturn(1);

        var counts = service.appendAdapterResults(
                "endpoint-1",
                ADAPTER,
                List.of(
                        codec.encodeSeedResult(success),
                        codec.encodeSeedResult(rejection)
                )
        );

        assertThat(counts.acceptedCount()).isEqualTo(1);
        assertThat(counts.rejectedCount()).isEqualTo(1);
        verify(resultRuntime).appendSeedResults(List.of(rejection));
    }

    @Test
    void allInvalidItemsDoNotCallTheRuntime() {
        var counts = service.appendAdapterResults(
                "endpoint-1",
                WORKER,
                List.of(
                        "not-json",
                        codec.encodeSeedResult(result(COMMAND_ID, "3001"))
                )
        );

        assertThat(counts.acceptedCount()).isZero();
        assertThat(counts.rejectedCount()).isEqualTo(2);
        verify(resultRuntime, never()).appendSeedResults(
                org.mockito.ArgumentMatchers.anyList()
        );
    }

    @Test
    void incompleteRuntimeAppendIsUnavailableForRetry() {
        SeedResult success = result(COMMAND_ID, "200");
        when(resultRuntime.appendSeedResults(List.of(success)))
                .thenReturn(0);

        assertThatThrownBy(() -> service.appendAdapterResults(
                "endpoint-1",
                WORKER,
                List.of(codec.encodeSeedResult(success))
        ))
                .isInstanceOf(ServerException.class)
                .extracting(
                        error -> ((ServerException) error).errorCode()
                )
                .isEqualTo(
                        ServerErrorCode.WORKER_DELIVERY_UNAVAILABLE
                );
    }

    @Test
    void systemPollingCannotUseAdapterBatchOperations() {
        assertThatThrownBy(() -> service.appendAdapterResults(
                WorkerDeliveryProtocol
                        .SYSTEM_POLLING_ENDPOINT_MANAGER_ID,
                WORKER,
                List.of(codec.encodeSeedResult(
                        result(COMMAND_ID, "200")
                ))
        )).isInstanceOf(ServerException.class);
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
}
