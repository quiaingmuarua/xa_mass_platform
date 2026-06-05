package com.xa.mass.api.filter;

import com.xa.mass.api.observability.ServerApiFailureAttributes;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiRequestSizeGuardFilterTest {

    @Test
    void oversizedTaskCreateSetsPayloadTooLargeFailureAttributes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");
        request.setContent(new byte[70 * 1024]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ApiRequestSizeGuardFilter().doFilter(request, response, new MockFilterChain());

        assertEquals(413, response.getStatus());
        assertEquals(ServerApiFailureAttributes.PAYLOAD_TOO_LARGE,
                request.getAttribute(ServerApiFailureAttributes.FAILURE_CLASS_ATTR));
        assertEquals("Request body exceeds size limit",
                request.getAttribute(ServerApiFailureAttributes.SAFE_MESSAGE_ATTR));
    }
}
