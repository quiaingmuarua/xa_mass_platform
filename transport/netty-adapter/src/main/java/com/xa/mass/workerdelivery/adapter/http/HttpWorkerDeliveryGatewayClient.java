package com.xa.mass.workerdelivery.adapter.http;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class HttpWorkerDeliveryGatewayClient
        implements WorkerDeliveryGatewayClient {

    private final HttpClient http;
    private final URI gatewayBaseUrl;
    private final Duration requestTimeout;
    private final WorkerDeliveryGatewayHttpContract contract;

    public HttpWorkerDeliveryGatewayClient(
            URI gatewayBaseUrl,
            Duration requestTimeout
    ) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(requireTimeout(requestTimeout))
                        .version(HttpClient.Version.HTTP_1_1)
                        .build(),
                gatewayBaseUrl,
                requestTimeout,
                new WorkerDeliveryCodec()
        );
    }

    HttpWorkerDeliveryGatewayClient(
            HttpClient http,
            URI gatewayBaseUrl,
            Duration requestTimeout,
            WorkerDeliveryCodec codec
    ) {
        this.http = Objects.requireNonNull(http, "http");
        this.gatewayBaseUrl = requireGatewayBaseUrl(gatewayBaseUrl);
        this.requestTimeout = requireTimeout(requestTimeout);
        contract = new WorkerDeliveryGatewayHttpContract(
                Objects.requireNonNull(codec, "codec")
        );
    }

    @Override
    public Map<String, DeliveryCommand> consumeWorkerCommands(
            String endpointManagerId,
            int limit
    ) {
        String operation = "gateway.consumeCommands";
        String body = contract.encodeConsumeRequest(limit);
        HttpResponse<String> response = send(
                request(endpointManagerId, "commands:consume")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                body,
                                StandardCharsets.UTF_8
                        ))
                        .build(),
                operation
        );
        if (response.statusCode() != 200) {
            throw statusFailure(
                    operation,
                    "Worker command consume",
                    response.statusCode()
            );
        }
        return contract.decodeConsumeResponse(response.body());
    }

    @Override
    public void appendResults(
            String endpointManagerId,
            List<String> encodedWorkerResults
    ) {
        String operation = "gateway.appendResults";
        String body = contract.encodeResultBatch(encodedWorkerResults);
        HttpResponse<String> response = send(
                request(endpointManagerId, "results:append")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                body,
                                StandardCharsets.UTF_8
                        ))
                        .build(),
                operation
        );
        if (response.statusCode() != 202) {
            throw response.statusCode() >= 500
                    ? statusFailure(
                            operation,
                            "DeliveryReport append",
                            response.statusCode()
                    )
                    : new WorkerDeliveryAdapterException(
                            WorkerDeliveryAdapterErrorCode
                                    .GATEWAY_PROTOCOL_ERROR,
                            operation,
                            "DeliveryReport append was rejected with HTTP "
                                    + response.statusCode(),
                            null
                    );
        }
        contract.requireCompleteResultResponse(
                response.body(),
                encodedWorkerResults.size()
        );
    }

    @Override
    public CompletionStage<Void> verifyWorkerRoute(
            String endpointManagerId,
            String workerId
    ) {
        String operation = "gateway.verifyWorkerRoute";
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException(
                    "workerId must be non-blank"
            );
        }
        HttpRequest bindingRequest = request(
                endpointManagerId,
                "workers/"
                        + encodePathSegment(workerId)
                        + ":verify-binding"
        ).POST(HttpRequest.BodyPublishers.noBody()).build();
        return http.sendAsync(
                bindingRequest,
                HttpResponse.BodyHandlers.discarding()
        ).handle((response, failure) -> {
            if (failure != null) {
                throw new WorkerDeliveryAdapterException(
                        WorkerDeliveryAdapterErrorCode
                                .GATEWAY_UNAVAILABLE,
                        operation,
                        "Worker route verification failed",
                        failure
                );
            }
            if (response.statusCode() != 204) {
                int statusCode = response.statusCode();
                if (statusCode >= 500) {
                    throw statusFailure(
                            operation,
                            "Worker route verification",
                            statusCode
                    );
                }
                WorkerDeliveryAdapterErrorCode errorCode =
                        statusCode >= 400
                                ? WorkerDeliveryAdapterErrorCode
                                .WORKER_ROUTE_REJECTED
                                : WorkerDeliveryAdapterErrorCode
                                .GATEWAY_PROTOCOL_ERROR;
                throw new WorkerDeliveryAdapterException(
                        errorCode,
                        operation,
                        "Worker route verification failed with HTTP "
                                + statusCode,
                        null
                );
            }
            return null;
        });
    }

    private HttpRequest.Builder request(
            String endpointManagerId,
            String action
    ) {
        if (endpointManagerId == null || endpointManagerId.isBlank()) {
            throw new IllegalArgumentException(
                    "endpointManagerId must be non-blank"
            );
        }
        String base = trimTrailingSlash(gatewayBaseUrl.toString());
        URI uri = URI.create(
                base
                        + "/api/v1/worker-delivery/endpoint-managers/"
                        + encodePathSegment(endpointManagerId)
                        + "/"
                        + action
        );
        return HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json");
    }

    private HttpResponse<String> send(
            HttpRequest request,
            String operation
    ) {
        try {
            return http.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(
                            StandardCharsets.UTF_8
                    )
            );
        } catch (IOException error) {
            throw new WorkerDeliveryAdapterException(
                    WorkerDeliveryAdapterErrorCode.GATEWAY_UNAVAILABLE,
                    operation,
                    "Worker Delivery Gateway request failed",
                    error
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new WorkerDeliveryAdapterException(
                    WorkerDeliveryAdapterErrorCode.GATEWAY_UNAVAILABLE,
                    operation,
                    "Worker Delivery Gateway request was interrupted",
                    error
            );
        }
    }

    private static WorkerDeliveryAdapterException statusFailure(
            String operation,
            String action,
            int statusCode
    ) {
        return new WorkerDeliveryAdapterException(
                WorkerDeliveryAdapterErrorCode.GATEWAY_UNAVAILABLE,
                operation,
                action + " failed with HTTP " + statusCode,
                null
        );
    }

    private static String trimTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private static URI requireGatewayBaseUrl(URI value) {
        Objects.requireNonNull(value, "gatewayBaseUrl");
        String scheme = value.getScheme();
        if (!value.isAbsolute()
                || value.getHost() == null
                || !("http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException(
                    "gatewayBaseUrl must be an absolute HTTP(S) URI"
            );
        }
        return value;
    }

    private static Duration requireTimeout(Duration value) {
        Objects.requireNonNull(value, "requestTimeout");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "requestTimeout must be positive"
            );
        }
        return value;
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
