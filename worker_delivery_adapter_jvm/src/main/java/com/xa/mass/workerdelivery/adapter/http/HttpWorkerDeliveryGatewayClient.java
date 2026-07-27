package com.xa.mass.workerdelivery.adapter.http;

import com.xa.mass.workerdelivery.adapter.application.WorkerCommandPage;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

public final class HttpWorkerDeliveryGatewayClient
        implements WorkerDeliveryGatewayClient {

    private final HttpClient http;
    private final URI gatewayBaseUrl;
    private final Duration requestTimeout;
    private final WorkerDeliveryGatewayHttpContract contract;

    public HttpWorkerDeliveryGatewayClient(
            URI gatewayBaseUrl,
            Duration requestTimeout,
            WorkerDeliveryCodec codec
    ) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(requestTimeout)
                        .version(HttpClient.Version.HTTP_1_1)
                        .build(),
                gatewayBaseUrl,
                requestTimeout,
                codec
        );
    }

    HttpWorkerDeliveryGatewayClient(
            HttpClient http,
            URI gatewayBaseUrl,
            Duration requestTimeout,
            WorkerDeliveryCodec codec
    ) {
        this.http = http;
        this.gatewayBaseUrl = gatewayBaseUrl;
        this.requestTimeout = requestTimeout;
        contract = new WorkerDeliveryGatewayHttpContract(codec);
    }

    @Override
    public WorkerCommandPage consumeWorkerCommands(
            String endpointManagerId,
            String cursor,
            int scanCount
    ) {
        String body = contract.encodeConsumeRequest(cursor, scanCount);
        HttpResponse<String> response = send(
                request(endpointManagerId, "commands:consume")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                body,
                                StandardCharsets.UTF_8
                        ))
                        .build()
        );
        if (response.statusCode() != 200) {
            throw statusFailure(
                    "Worker command consume",
                    response.statusCode()
            );
        }
        return contract.decodeConsumeResponse(response.body());
    }

    @Override
    public void appendResults(
            String endpointManagerId,
            List<SeedResult> results
    ) {
        String body = contract.encodeResultBatch(results);
        HttpResponse<String> response = send(
                request(endpointManagerId, "results:append")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                body,
                                StandardCharsets.UTF_8
                        ))
                        .build()
        );
        if (response.statusCode() != 202) {
            throw statusFailure(
                    "SeedResult append",
                    response.statusCode()
            );
        }
        int accepted = contract.decodeAcceptedCount(response.body());
        if (accepted != results.size()) {
            throw new WorkerDeliveryAdapterException(
                    "SeedResult batch was not fully accepted"
            );
        }
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

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(
                            StandardCharsets.UTF_8
                    )
            );
        } catch (IOException error) {
            throw new WorkerDeliveryAdapterException(
                    "Worker Delivery Gateway request failed",
                    error
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new WorkerDeliveryAdapterException(
                    "Worker Delivery Gateway request was interrupted",
                    error
            );
        }
    }

    private static WorkerDeliveryAdapterException statusFailure(
            String operation,
            int statusCode
    ) {
        return new WorkerDeliveryAdapterException(
                operation + " failed with HTTP " + statusCode
        );
    }

    private static String trimTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
