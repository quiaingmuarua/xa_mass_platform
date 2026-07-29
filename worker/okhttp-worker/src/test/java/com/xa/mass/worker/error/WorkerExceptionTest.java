package com.xa.mass.worker.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkerExceptionTest {

    @Test
    void errorCodesAreUniqueAndStayInWorkerRange() {
        Set<Integer> codes = new HashSet<>();
        Arrays.stream(WorkerErrorCode.values()).forEach(errorCode -> {
            int code = errorCode.code();
            assertTrue(code >= 30_000 && code <= 39_999);
            assertTrue(codes.add(code));
        });
    }

    @Test
    void exceptionCarriesOnlyWorkerCodeOperationMessageAndCause() {
        IllegalStateException cause = new IllegalStateException("network");
        WorkerException exception = new WorkerException(
                WorkerErrorCode.COMMAND_POLL_FAILED,
                "polling.pollCommand",
                null,
                cause
        );

        assertEquals(
                WorkerErrorCode.COMMAND_POLL_FAILED,
                exception.errorCode()
        );
        assertEquals("polling.pollCommand", exception.operation());
        assertEquals(
                WorkerErrorCode.COMMAND_POLL_FAILED.defaultMessage(),
                exception.getMessage()
        );
        assertSame(cause, exception.getCause());
    }
}
