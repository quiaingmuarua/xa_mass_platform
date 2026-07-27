package com.xa.mass.server.kernelclient;

import com.xa.mass.server.api.v1.model.CommandResultResponse;
import com.xa.mass.server.api.v1.model.RuntimeCommandStatus;
import com.xa.mass.server.api.v1.model.TaskCreateRequest;
import com.xa.mass.server.api.v1.model.WorkerGroupUpsertRequest;
import com.xa.mass.server.api.v1.model.WorkerUpsertRequest;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
final class HttpKernelCommandClient implements KernelCommandClient {

    private final RestClient restClient;

    HttpKernelCommandClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public KernelResponse<CommandResultResponse> upsertWorkerGroup(
            String workerGroupId,
            WorkerGroupUpsertRequest request
    ) {
        return exchangeCommand(
                restClient.put()
                        .uri("/worker-groups/{workerGroupId}", workerGroupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
        );
    }

    @Override
    public KernelResponse<CommandResultResponse> upsertWorker(
            String workerGroupId,
            String workerId,
            WorkerUpsertRequest request
    ) {
        return exchangeCommand(
                restClient.put()
                        .uri(
                                "/worker-groups/{workerGroupId}/workers/{workerId}",
                                workerGroupId,
                                workerId
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
        );
    }

    @Override
    public KernelResponse<CommandResultResponse> createTask(
            TaskCreateRequest request
    ) {
        return exchangeCommand(
                restClient.post()
                        .uri("/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
        );
    }

    @Override
    public KernelResponse<CommandResultResponse> approveTask(String taskId) {
        return exchangeCommand(
                restClient.post().uri("/tasks/{taskId}/approve", taskId)
        );
    }

    @Override
    public KernelResponse<CommandResultResponse> closeTask(String taskId) {
        return exchangeCommand(
                restClient.post().uri("/tasks/{taskId}/close", taskId)
        );
    }

    @Override
    public boolean isHealthy() {
        try {
            return restClient.get()
                    .uri("/health")
                    .exchange((ignoredRequest, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            return false;
                        }
                        Map<String, Object> body = readMap(response);
                        return "ok".equals(body.get("status"));
                    });
        } catch (RuntimeException error) {
            return false;
        }
    }

    private KernelResponse<CommandResultResponse> exchangeCommand(
            RestClient.RequestHeadersSpec<?> request
    ) {
        try {
            return request.exchange((ignoredRequest, response) -> {
                Map<String, Object> body = readMap(response);
                if (body.containsKey("status")) {
                    return new KernelResponse<>(
                            response.getStatusCode(),
                            parseCommandResult(body)
                    );
                }
                if (response.getStatusCode().isError()) {
                    throw rejectedResponse(response.getStatusCode().value(), body);
                }
                throw KernelClientException.invalidResponse(
                        "Kernel command response is missing status"
                );
            });
        } catch (KernelClientException error) {
            throw error;
        } catch (ResourceAccessException error) {
            throw transportFailure(error);
        } catch (RestClientException error) {
            throw KernelClientException.invalidResponse(
                    "Kernel command response could not be decoded",
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
                throw KernelClientException.invalidResponse(
                        "Kernel command response body is empty"
                );
            }
            return body;
        } catch (KernelClientException error) {
            throw error;
        } catch (RuntimeException error) {
            throw KernelClientException.invalidResponse(
                    "Kernel command response body is not valid JSON",
                    error
            );
        }
    }

    private static CommandResultResponse parseCommandResult(Map<?, ?> body) {
        Object statusValue = body.get("status");
        if (!(statusValue instanceof String statusText)) {
            throw KernelClientException.invalidResponse(
                    "Kernel command status is missing or invalid"
            );
        }
        RuntimeCommandStatus status;
        try {
            status = RuntimeCommandStatus.fromWireValue(statusText);
        } catch (IllegalArgumentException error) {
            throw KernelClientException.invalidResponse(
                    "Kernel command status is unknown",
                    error
            );
        }
        Object reasonValue = body.get("reason");
        if (reasonValue != null && !(reasonValue instanceof String)) {
            throw KernelClientException.invalidResponse(
                    "Kernel command reason is invalid"
            );
        }
        return new CommandResultResponse(status, (String) reasonValue);
    }

    private static KernelClientException rejectedResponse(
            int statusCode,
            Map<String, Object> body
    ) {
        HttpStatus status = HttpStatus.resolve(statusCode);
        if (status == null) {
            throw KernelClientException.invalidResponse(
                    "Kernel returned an unsupported HTTP status"
            );
        }
        Object detail = body.get("detail");
        String reason = detail == null
                ? "Kernel rejected the runtime command"
                : String.valueOf(detail);
        return KernelClientException.rejected(status, reason);
    }

    private static KernelClientException transportFailure(
            ResourceAccessException error
    ) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof SocketTimeoutException
                    || cause instanceof HttpTimeoutException) {
                return KernelClientException.timeout(error);
            }
            cause = cause.getCause();
        }
        return KernelClientException.unavailable(error);
    }
}
