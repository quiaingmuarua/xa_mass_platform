package com.xa.mass.workerdelivery.adapter.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class WorkerDeliveryAdapterExceptionTest {

    @Test
    void errorCodesAreUniqueAndStayInTheAdapterRange() {
        int[] codes = Arrays.stream(
                        WorkerDeliveryAdapterErrorCode.values()
                )
                .mapToInt(WorkerDeliveryAdapterErrorCode::code)
                .toArray();

        for (int code : codes) {
            assertThat(code).isBetween(20_000, 29_999);
        }
        assertThat(codes).doesNotHaveDuplicates();
    }

    @Test
    void exceptionPreservesItsOperationAndCause() {
        IllegalStateException cause =
                new IllegalStateException("gateway offline");
        WorkerDeliveryAdapterException error =
                new WorkerDeliveryAdapterException(
                        WorkerDeliveryAdapterErrorCode.GATEWAY_UNAVAILABLE,
                        "gateway.consumeCommands",
                        null,
                        cause
                );

        assertThat(error.errorCode()).isEqualTo(
                WorkerDeliveryAdapterErrorCode.GATEWAY_UNAVAILABLE
        );
        assertThat(error.operation())
                .isEqualTo("gateway.consumeCommands");
        assertThat(error.getMessage())
                .isEqualTo("Worker Delivery Gateway is unavailable");
        assertThat(error.getCause()).isSameAs(cause);
    }
}
