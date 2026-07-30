package com.xa.mass.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.server.api.v1.model.ApiErrorResponse;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiExceptionHandlerTest {

    @ParameterizedTest
    @MethodSource("statusMappings")
    void mapsEveryServerCodeToItsExplicitHttpBoundary(
            ServerErrorCode errorCode,
            HttpStatus expectedStatus
    ) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                RequestIdFilter.ATTRIBUTE_NAME,
                "request-1"
        );
        ServerException failure = new ServerException(
                errorCode,
                "test.execute",
                null,
                null
        );

        ResponseEntity<ApiErrorResponse> response =
                new ApiExceptionHandler().serverFailure(
                        failure,
                        request
                );

        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        assertThat(response.getBody()).isEqualTo(new ApiErrorResponse(
                errorCode.code(),
                errorCode.defaultMessage(),
                "request-1"
        ));
    }

    private static Stream<Arguments> statusMappings() {
        return Stream.of(
                Arguments.of(
                        ServerErrorCode.KERNEL_UNAVAILABLE,
                        HttpStatus.SERVICE_UNAVAILABLE
                ),
                Arguments.of(
                        ServerErrorCode.KERNEL_TIMEOUT,
                        HttpStatus.GATEWAY_TIMEOUT
                ),
                Arguments.of(
                        ServerErrorCode.INVALID_KERNEL_RESPONSE,
                        HttpStatus.BAD_GATEWAY
                ),
                Arguments.of(
                        ServerErrorCode.KERNEL_REJECTED_NOT_FOUND,
                        HttpStatus.NOT_FOUND
                ),
                Arguments.of(
                        ServerErrorCode.KERNEL_REJECTED_CONFLICT,
                        HttpStatus.CONFLICT
                ),
                Arguments.of(
                        ServerErrorCode.KERNEL_REJECTED_INVALID,
                        HttpStatus.UNPROCESSABLE_ENTITY
                ),
                Arguments.of(
                        ServerErrorCode.KERNEL_REJECTED_RETRYABLE,
                        HttpStatus.SERVICE_UNAVAILABLE
                ),
                Arguments.of(
                        ServerErrorCode.INVALID_TASK_DATA_REQUEST,
                        HttpStatus.BAD_REQUEST
                ),
                Arguments.of(
                        ServerErrorCode.TASK_NOT_FOUND,
                        HttpStatus.NOT_FOUND
                ),
                Arguments.of(
                        ServerErrorCode.TASK_DATA_UNAVAILABLE,
                        HttpStatus.SERVICE_UNAVAILABLE
                ),
                Arguments.of(
                        ServerErrorCode.TASK_RPC_CAPACITY_EXCEEDED,
                        HttpStatus.TOO_MANY_REQUESTS
                ),
                Arguments.of(
                        ServerErrorCode.INVALID_WORKER_DELIVERY_REQUEST,
                        HttpStatus.BAD_REQUEST
                ),
                Arguments.of(
                        ServerErrorCode.WORKER_DELIVERY_UNAVAILABLE,
                        HttpStatus.SERVICE_UNAVAILABLE
                ),
                Arguments.of(
                        ServerErrorCode.MALFORMED_REQUEST,
                        HttpStatus.BAD_REQUEST
                )
        );
    }
}
