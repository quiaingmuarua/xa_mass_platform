package com.xa.mass.workerdelivery.adapter.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(Arrays.stream(
                        WorkerDeliveryAdapterErrorCode.values()
                )
                .map(WorkerDeliveryAdapterErrorCode::defaultMessage))
                .allMatch(message -> !message.isBlank());
    }

    @Test
    void exceptionPreservesItsOperationAndCause() {
        IllegalStateException cause =
                new IllegalStateException("remote API offline");
        WorkerDeliveryAdapterException error =
                new WorkerDeliveryAdapterException(
                        WorkerDeliveryAdapterErrorCode.REMOTE_API_UNAVAILABLE,
                        "deliveryCommand.consumeRemote",
                        null,
                        cause
                );

        assertThat(error.errorCode()).isEqualTo(
                WorkerDeliveryAdapterErrorCode.REMOTE_API_UNAVAILABLE
        );
        assertThat(error.operation())
                .isEqualTo("deliveryCommand.consumeRemote");
        assertThat(error.getMessage())
                .isEqualTo("Worker Delivery remote API is unavailable");
        assertThat(error.getCause()).isSameAs(cause);
    }

    @Test
    void exceptionRequiresItsOwnerCodeAndOwnerMethodOperation() {
        assertThatThrownBy(() -> new WorkerDeliveryAdapterException(
                null,
                "deliveryCommand.consumeRemote",
                null,
                null
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new WorkerDeliveryAdapterException(
                WorkerDeliveryAdapterErrorCode.REMOTE_API_UNAVAILABLE,
                null,
                null,
                null
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new WorkerDeliveryAdapterException(
                WorkerDeliveryAdapterErrorCode.REMOTE_API_UNAVAILABLE,
                "consumeCommands",
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
