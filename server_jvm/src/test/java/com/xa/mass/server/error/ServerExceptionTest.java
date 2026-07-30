package com.xa.mass.server.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ServerExceptionTest {

    @Test
    void errorCodesAreUniqueAndStayInTheServerRange() {
        int[] codes = Arrays.stream(ServerErrorCode.values())
                .mapToInt(ServerErrorCode::code)
                .toArray();

        for (int code : codes) {
            assertThat(code).isBetween(10_000, 19_999);
        }
        assertThat(codes).doesNotHaveDuplicates();
        assertThat(Arrays.stream(ServerErrorCode.values())
                .map(ServerErrorCode::defaultMessage))
                .allMatch(message -> !message.isBlank());
    }

    @Test
    void exceptionCarriesOnlyItsCodeOperationMessageAndCause() {
        IllegalStateException cause =
                new IllegalStateException("redis unavailable");
        ServerException error = new ServerException(
                ServerErrorCode.TASK_DATA_UNAVAILABLE,
                "taskData.appendItems",
                null,
                cause
        );

        assertThat(error.errorCode())
                .isEqualTo(ServerErrorCode.TASK_DATA_UNAVAILABLE);
        assertThat(error.operation()).isEqualTo("taskData.appendItems");
        assertThat(error.getMessage())
                .isEqualTo("Task data Redis is unavailable");
        assertThat(error.getCause()).isSameAs(cause);
    }

    @Test
    void exceptionRequiresItsOwnerCodeAndOwnerMethodOperation() {
        assertThatThrownBy(() -> new ServerException(
                null,
                "taskData.appendItems",
                null,
                null
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new ServerException(
                ServerErrorCode.TASK_DATA_UNAVAILABLE,
                null,
                null,
                null
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new ServerException(
                ServerErrorCode.TASK_DATA_UNAVAILABLE,
                "appendItems",
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
