package com.xa.mass.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.server.api.v1.model.ApiErrorResponse;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiExceptionHandlerTest {

    @Test
    void publicBusinessCodesAreUniqueAndHaveDefaultMessages() {
        assertThat(Arrays.stream(ServerErrorCode.values())
                .map(ServerErrorCode::code)
                .toList()).doesNotHaveDuplicates();
        assertThat(Arrays.stream(ServerErrorCode.values())
                .map(ServerErrorCode::defaultMessage)
                .toList()).allSatisfy(message ->
                        assertThat(message).isNotBlank());
    }

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
                "internal owner detail",
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
        return Stream.of(ServerErrorCode.values())
                .map(errorCode -> Arguments.of(
                        errorCode,
                        expectedStatus(errorCode)
                ));
    }

    private static HttpStatus expectedStatus(ServerErrorCode errorCode) {
        return switch (errorCode) {
            case TASK_DATA_UNAVAILABLE,
                    TASK_CALL_REGISTRATION_UNAVAILABLE,
                    WORKER_DELIVERY_UNAVAILABLE,
                    WORKER_IDENTITY_UNAVAILABLE,
                    WORKER_BINDING_UNAVAILABLE,
                    WORKER_ENDPOINT_UNAVAILABLE,
                    RUNTIME_VIEW_UNAVAILABLE,
                    WORKER_SCHEDULING_UNAVAILABLE,
                    WORKER_GROUP_REGISTRATION_UNAVAILABLE,
                    WORKER_RESOURCE_UNAVAILABLE,
                    DIRECT_CALL_UNAVAILABLE ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            case DIRECT_CALL_CAPACITY_EXCEEDED ->
                    HttpStatus.TOO_MANY_REQUESTS;
            case KERNEL_REJECTED_CONFLICT,
                    INVALID_TASK_DATA_REQUEST,
                    TASK_NOT_FOUND,
                    TASK_CALL_NOT_REGISTERED,
                    TASK_CALL_REGISTRATION_CONFLICT,
                    TASK_OPERATION_NOT_SUPPORTED,
                    TASK_STATE_CONFLICT,
                    TASK_RESULTS_NOT_READY,
                    TASK_WORKER_GROUP_NOT_FOUND,
                    INVALID_WORKER_DELIVERY_REQUEST,
                    INVALID_WORKER_IDENTITY_REQUEST,
                    WORKER_IDENTITY_NOT_FOUND,
                    WORKER_IDENTITY_CONFLICT,
                    INVALID_WORKER_BINDING_REQUEST,
                    WORKER_BINDING_NOT_FOUND,
                    WORKER_BINDING_CONFLICT,
                    WORKER_GROUP_NOT_FOUND,
                    RUNTIME_VIEW_FILTER_NOT_AVAILABLE,
                    INVALID_WORKER_GROUP_REQUEST,
                    WORKER_GROUP_REGISTRATION_CONFLICT,
                    WORKER_RESOURCE_NOT_FOUND,
                    WORKER_RESOURCE_STATE_CONFLICT,
                    INVALID_WORKER_RESOURCE_REQUEST,
                    INVALID_DIRECT_CALL_REQUEST,
                    DIRECT_CALL_TARGET_NOT_FOUND,
                    MALFORMED_REQUEST -> HttpStatus.BAD_REQUEST;
        };
    }
}
