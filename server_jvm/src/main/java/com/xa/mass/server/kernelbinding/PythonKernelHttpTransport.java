package com.xa.mass.server.kernelbinding;

import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

final class PythonKernelHttpTransport {

    private static final String EXCHANGE_OPERATION =
            "kernelBinding.exchange";
    private static final String DECODE_OPERATION =
            "kernelBinding.decodeResponse";

    private final RestClient restClient;

    PythonKernelHttpTransport(RestClient restClient) {
        this.restClient = restClient;
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
                throw new ServerException(
                        ServerErrorCode.INVALID_KERNEL_RESPONSE,
                        EXCHANGE_OPERATION,
                        "Kernel response is missing status",
                        null
                );
            });
        } catch (ServerException error) {
            throw error;
        } catch (ResourceAccessException error) {
            throw transportFailure(error);
        } catch (RestClientException error) {
            throw new ServerException(
                    ServerErrorCode.INVALID_KERNEL_RESPONSE,
                    DECODE_OPERATION,
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
                throw new ServerException(
                        ServerErrorCode.INVALID_KERNEL_RESPONSE,
                        DECODE_OPERATION,
                        "Kernel response body is empty",
                        null
                );
            }
            return body;
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ServerException(
                    ServerErrorCode.INVALID_KERNEL_RESPONSE,
                    DECODE_OPERATION,
                    "Kernel response body is not valid JSON",
                    error
            );
        }
    }

    private static ServerException rejectedResponse(
            int statusCode,
            Map<String, Object> body
    ) {
        Object detail = body.get("detail");
        ServerErrorCode errorCode = switch (statusCode) {
            case 404 -> ServerErrorCode.KERNEL_REJECTED_NOT_FOUND;
            case 409 -> ServerErrorCode.KERNEL_REJECTED_CONFLICT;
            case 422 -> ServerErrorCode.KERNEL_REJECTED_INVALID;
            case 503 -> ServerErrorCode.KERNEL_REJECTED_RETRYABLE;
            default -> ServerErrorCode.INVALID_KERNEL_RESPONSE;
        };
        return new ServerException(
                errorCode,
                EXCHANGE_OPERATION,
                detail == null
                        ? errorCode.defaultMessage()
                        : String.valueOf(detail),
                null
        );
    }

    private static ServerException transportFailure(
            ResourceAccessException error
    ) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof SocketTimeoutException
                    || cause instanceof HttpTimeoutException) {
                return new ServerException(
                        ServerErrorCode.KERNEL_TIMEOUT,
                        EXCHANGE_OPERATION,
                        null,
                        error
                );
            }
            cause = cause.getCause();
        }
        return new ServerException(
                ServerErrorCode.KERNEL_UNAVAILABLE,
                EXCHANGE_OPERATION,
                null,
                error
        );
    }
}
