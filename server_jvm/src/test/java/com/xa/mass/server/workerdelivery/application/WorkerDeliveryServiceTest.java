package com.xa.mass.server.workerdelivery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.delivery.WorkerResultRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.server.workerbinding.WorkerBindingService;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerDeliveryServiceTest {

    private static final String COMMAND_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";
    private static final String POLLING =
            WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID;
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private WorkerCommandRuntime commandRuntime;
    private WorkerResultRuntime resultRuntime;
    private WorkerBindingService bindings;
    private WorkerDeliveryService service;

    @BeforeEach
    void setUp() {
        commandRuntime = mock(WorkerCommandRuntime.class);
        resultRuntime = mock(WorkerResultRuntime.class);
        bindings = mock(WorkerBindingService.class);
        service = new WorkerDeliveryService(
                commandRuntime,
                resultRuntime,
                bindings
        );
    }

    @Test
    void pointPollDropsACommandThatExpiredAfterRedisConsumption() {
        when(commandRuntime.consumeWorkerCommand(POLLING, "worker-1"))
                .thenReturn(new WorkerCommand(
                        COMMAND_ID,
                        WorkerMessageEndpoint.TASK,
                        WorkerMessageEndpoint.WORKER,
                        "test.event",
                        System.currentTimeMillis() - 1,
                        "item",
                        "context"
                ));

        assertThat(service.pollWorkerCommand(POLLING, "worker-1"))
                .isNull();
        verify(bindings).requireCurrentEndpoint(POLLING, "worker-1");
    }

    @Test
    void workerResultRejectsAdapterEvidence() {
        WorkerResult result = result(COMMAND_ID, "23002");

        assertThatThrownBy(() -> service.appendWorkerResult(
                POLLING,
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
        verify(resultRuntime, never()).appendWorkerResults(List.of(result));
        verify(bindings).requireCurrentEndpoint(POLLING, "worker-1");
    }

    @Test
    void adapterBatchAcceptsAllResultClassesAndRejectsMalformedItems() {
        WorkerResult success = result(COMMAND_ID, "200");
        WorkerResult failure = result(
                "9f0d983c-8010-4d59-a6d2-e8fedb8d0059",
                "3500"
        );
        WorkerResult forgedRejection = result(
                "66f60ac8-e68f-4783-90e3-13b20a54ca13",
                "23002"
        );
        List<WorkerResult> accepted = List.of(
                success,
                failure,
                forgedRejection
        );
        when(resultRuntime.appendWorkerResults(accepted))
                .thenReturn(accepted.size());

        var counts = service.appendAdapterResults(
                "endpoint-1",
                java.util.Arrays.asList(
                        codec.encodeWorkerResult(success),
                        "not-json",
                        null,
                        codec.encodeWorkerResult(failure),
                        codec.encodeWorkerResult(forgedRejection)
                )
        );

        assertThat(counts.acceptedCount()).isEqualTo(3);
        assertThat(counts.rejectedCount()).isEqualTo(2);
        verify(resultRuntime).appendWorkerResults(accepted);
    }

    @Test
    void adapterBatchRejectsAResultForAnotherDestination() {
        WorkerResult success = result(COMMAND_ID, "200");
        WorkerResult wrongDestination = new WorkerResult(
                "9f0d983c-8010-4d59-a6d2-e8fedb8d0059",
                WorkerMessageEndpoint.SYSTEM,
                "test.event",
                "23002",
                "null",
                "context"
        );
        when(resultRuntime.appendWorkerResults(List.of(success)))
                .thenReturn(1);

        var counts = service.appendAdapterResults(
                "endpoint-1",
                List.of(
                        codec.encodeWorkerResult(success),
                        codec.encodeWorkerResult(wrongDestination)
                )
        );

        assertThat(counts.acceptedCount()).isEqualTo(1);
        assertThat(counts.rejectedCount()).isEqualTo(1);
        verify(resultRuntime).appendWorkerResults(List.of(success));
    }

    @Test
    void allInvalidItemsDoNotCallTheRuntime() {
        var counts = service.appendAdapterResults(
                "endpoint-1",
                List.of(
                        "not-json",
                        ""
                )
        );

        assertThat(counts.acceptedCount()).isZero();
        assertThat(counts.rejectedCount()).isEqualTo(2);
        verify(resultRuntime, never()).appendWorkerResults(
                org.mockito.ArgumentMatchers.anyList()
        );
    }

    @Test
    void incompleteRuntimeAppendIsUnavailableForRetry() {
        WorkerResult success = result(COMMAND_ID, "200");
        when(resultRuntime.appendWorkerResults(List.of(success)))
                .thenReturn(0);

        assertThatThrownBy(() -> service.appendAdapterResults(
                "endpoint-1",
                List.of(codec.encodeWorkerResult(success))
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
                List.of(codec.encodeWorkerResult(
                        result(COMMAND_ID, "200")
                ))
        )).isInstanceOf(ServerException.class);
    }

    private static WorkerResult result(
            String messageId,
            String outcomeCode
    ) {
        return new WorkerResult(
                messageId,
                WorkerMessageEndpoint.TASK,
                "test.event",
                outcomeCode,
                "null",
                "context"
        );
    }
}
