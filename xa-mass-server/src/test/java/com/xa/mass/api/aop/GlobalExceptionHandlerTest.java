package com.xa.mass.api.aop;

import com.xa.mass.api.observability.ServerApiFailureAttributes;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void badJsonSetsBoundedSafeFailureAttributes() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");

        handler.handleBadJson(new HttpMessageNotReadableException("Authorization: Bearer secret"), request);

        assertEquals(ServerApiFailureAttributes.BAD_REQUEST,
                request.getAttribute(ServerApiFailureAttributes.FAILURE_CLASS_ATTR));
        assertEquals("Request body is invalid",
                request.getAttribute(ServerApiFailureAttributes.SAFE_MESSAGE_ATTR));
    }

    @Test
    void unhandledExceptionSetsGenericSafeFailureAttributes() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");

        handler.handleException(new RuntimeException("database unavailable"), request);

        assertEquals(ServerApiFailureAttributes.UNHANDLED,
                request.getAttribute(ServerApiFailureAttributes.FAILURE_CLASS_ATTR));
        assertEquals("Internal Server Error",
                request.getAttribute(ServerApiFailureAttributes.SAFE_MESSAGE_ATTR));
    }
}
