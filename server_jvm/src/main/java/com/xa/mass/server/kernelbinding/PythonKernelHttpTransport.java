package com.xa.mass.server.kernelbinding;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

final class PythonKernelHttpTransport {

    private final RestClient restClient;

    PythonKernelHttpTransport(RestClient restClient) {
        this.restClient = restClient;
    }

    Map<String, Object> put(
            String path,
            Object body,
            Object... uriVariables
    ) {
        return exchange(restClient.put()
                .uri(path, uriVariables)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body));
    }

    Map<String, Object> postBody(
            String path,
            Object body,
            Object... uriVariables
    ) {
        return exchange(restClient.post()
                .uri(path, uriVariables)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body));
    }

    Map<String, Object> post(String path, Object... uriVariables) {
        return exchange(restClient.post().uri(path, uriVariables));
    }

    boolean isHealthy() {
        try {
            return restClient.get()
                    .uri("/health")
                    .exchange((ignored, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            return false;
                        }
                        return "ok".equals(readMap(response).get("status"));
                    });
        } catch (RuntimeException error) {
            return false;
        }
    }

    private Map<String, Object> exchange(
            RestClient.RequestHeadersSpec<?> request
    ) {
        try {
            return request.exchange((ignoredRequest, response) -> {
                Map<String, Object> body = readMap(response);
                if (body.containsKey("status")) {
                    return body;
                }
                if (response.getStatusCode().isError()) {
                    throw rejectedResponse(
                            response.getStatusCode().value(),
                            body
                    );
                }
                throw PythonKernelBindingException.invalidResponse(
                        "Kernel response is missing status"
                );
            });
        } catch (PythonKernelBindingException error) {
            throw error;
        } catch (ResourceAccessException error) {
            throw transportFailure(error);
        } catch (RestClientException error) {
            throw PythonKernelBindingException.invalidResponse(
                    "Kernel response could not be decoded",
                    error
            );
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMap(
            RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response
    ) {
        try {
            Map<String, Object> body = response.bodyTo(Map.class);
            if (body == null) {
                throw PythonKernelBindingException.invalidResponse(
                        "Kernel response body is empty"
                );
            }
            return body;
        } catch (PythonKernelBindingException error) {
            throw error;
        } catch (RuntimeException error) {
            throw PythonKernelBindingException.invalidResponse(
                    "Kernel response body is not valid JSON",
                    error
            );
        }
    }

    private static PythonKernelBindingException rejectedResponse(
            int statusCode,
            Map<String, Object> body
    ) {
        HttpStatus status = HttpStatus.resolve(statusCode);
        if (status == null) {
            throw PythonKernelBindingException.invalidResponse(
                    "Kernel returned an unsupported HTTP status"
            );
        }
        Object detail = body.get("detail");
        return PythonKernelBindingException.rejected(
                status,
                detail == null
                        ? "Kernel rejected the operation"
                        : String.valueOf(detail)
        );
    }

    private static PythonKernelBindingException transportFailure(
            ResourceAccessException error
    ) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof SocketTimeoutException
                    || cause instanceof HttpTimeoutException) {
                return PythonKernelBindingException.timeout(error);
            }
            cause = cause.getCause();
        }
        return PythonKernelBindingException.unavailable(error);
    }
}
