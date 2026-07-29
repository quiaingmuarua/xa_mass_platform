package com.xa.mass.foundation.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CodedRuntimeExceptionTest {

    @Test
    void defaultConstructorUsesOwnerCodeAndDefaultMessage() {
        TestException error = new TestException(
                TestError.INVALID,
                "test.validate",
                null,
                null
        );

        assertSame(TestError.INVALID, error.errorCode());
        assertEquals(1001, error.errorCode().code());
        assertEquals("test.validate", error.operation());
        assertEquals("Test input is invalid", error.getMessage());
        assertNull(error.getCause());
    }

    @Test
    void customMessageAndCauseArePreserved() {
        IllegalStateException cause =
                new IllegalStateException("source failure");
        TestException error = new TestException(
                TestError.UNAVAILABLE,
                "test.load",
                "Custom failure",
                cause
        );

        assertSame(TestError.UNAVAILABLE, error.errorCode());
        assertEquals("test.load", error.operation());
        assertEquals("Custom failure", error.getMessage());
        assertSame(cause, error.getCause());
    }

    @Test
    void causeConstructorUsesDefaultMessage() {
        IllegalStateException cause =
                new IllegalStateException("source failure");
        TestException error = new TestException(
                TestError.UNAVAILABLE,
                "test.load",
                null,
                cause
        );

        assertEquals("Test dependency is unavailable", error.getMessage());
        assertSame(cause, error.getCause());
    }

    @Test
    void errorCodeIsRequiredForEveryConstructor() {
        assertThrows(
                NullPointerException.class,
                () -> new TestException(
                        null,
                        "test.load",
                        "message",
                        new IllegalStateException()
                )
        );
    }

    @Test
    void operationMustBePresentAndNonBlank() {
        assertThrows(
                NullPointerException.class,
                () -> new TestException(
                        TestError.INVALID,
                        null,
                        null,
                        null
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new TestException(
                        TestError.INVALID,
                        " ",
                        null,
                        null
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new TestException(
                        TestError.INVALID,
                        "load",
                        null,
                        null
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new TestException(
                        TestError.INVALID,
                        "test load",
                        null,
                        null
                )
        );
    }

    private enum TestError implements ErrorCode {
        INVALID(1001, "Test input is invalid"),
        UNAVAILABLE(
                1002,
                "Test dependency is unavailable"
        );

        private final int code;
        private final String defaultMessage;

        TestError(int code, String defaultMessage) {
            this.code = code;
            this.defaultMessage = defaultMessage;
        }

        @Override
        public int code() {
            return code;
        }

        @Override
        public String defaultMessage() {
            return defaultMessage;
        }
    }

    private static final class TestException
            extends CodedRuntimeException {

        private TestException(
                TestError errorCode,
                String operation,
                String message,
                Throwable cause
        ) {
            super(errorCode, operation, message, cause);
        }

        @Override
        public TestError errorCode() {
            return (TestError) super.errorCode();
        }
    }
}
