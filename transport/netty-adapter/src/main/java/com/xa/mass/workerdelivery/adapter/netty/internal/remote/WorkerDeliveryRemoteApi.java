package com.xa.mass.workerdelivery.adapter.netty.internal.remote;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.json.Jsons;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/** Immutable Server HTTP facade shared by one Adapter process factory. */
public final class WorkerDeliveryRemoteApi {

    public static final int MAX_RESULTS_PER_APPEND = 100;

    private static final ThreadFactory HTTP_THREAD_FACTORY = Thread.ofVirtual()
            .name("worker-delivery-http-", 0)
            .factory();
    private static final Executor HTTP_EXECUTOR = command ->
            HTTP_THREAD_FACTORY.newThread(command).start();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .executor(HTTP_EXECUTOR)
            .build();

    private static final String COMMAND_OPERATION =
            "deliveryCommand.consumeRemote";
    private static final String COMMAND_DECODE_OPERATION =
            "deliveryCommand.decodeRemoteResponse";
    private static final String REPORT_OPERATION =
            "deliveryReport.submitRemote";
    private static final String REPORT_ENCODE_OPERATION =
            "deliveryReport.encodeRemoteRequest";
    private static final String REPORT_DECODE_OPERATION =
            "deliveryReport.decodeRemoteResponse";
    private static final String ROUTE_OPERATION =
            "workerConnection.verifyRoute";
    private static final Set<String> APPEND_FIELDS = Set.of(
            "acceptedCount",
            "rejectedCount"
    );

    private final String baseUrl;
    private final Duration requestTimeout;
    private final WorkerDeliveryCodec codec;

    public WorkerDeliveryRemoteApi(
            URI baseUrl,
            Duration requestTimeout,
            WorkerDeliveryCodec codec
    ) {
        this.baseUrl = trimTrailingSlash(
                requireBaseUrl(baseUrl).toString()
        );
        this.requestTimeout = requireTimeout(requestTimeout);
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public Map<String, DeliveryCommand> consumeCommands(
            String adapterId,
            int limit
    ) {
        HttpResponse<String> response = postJson(
                commandPath(adapterId),
                encodeConsumeRequest(limit),
                COMMAND_OPERATION,
                "Delivery Command acquisition failed"
        );
        if (response.statusCode() != 200) {
            throw commandStatusFailure(response.statusCode());
        }
        return decodeConsumeResponse(responseBody(response), limit);
    }

    public void appendReports(
            String adapterId,
            List<String> encodedReports
    ) {
        HttpResponse<String> response = postJson(
                reportPath(adapterId),
                encodeResultBatch(encodedReports),
                REPORT_OPERATION,
                "Worker result submission failed"
        );
        if (response.statusCode() != 202) {
            throw reportStatusFailure(response.statusCode());
        }
        requireCompleteResultResponse(
                responseBody(response),
                encodedReports.size()
        );
    }

    public CompletionStage<Void> verifyRoute(
            String adapterId,
            String workerId
    ) {
        CompletionStage<HttpResponse<String>> request;
        try {
            request = postEmptyAsync(
                    routePath(adapterId, workerId),
                    ROUTE_OPERATION,
                    "Worker route verification failed"
            );
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(
                    unavailable(
                            ROUTE_OPERATION,
                            "Worker route verification failed",
                            error
                    )
            );
        }
        return request.thenApply(response -> {
            if (response.statusCode() != 204) {
                throw routeStatusFailure(response.statusCode());
            }
            return null;
        });
    }

    private HttpResponse<String> postJson(
            String relativePath,
            String jsonBody,
            String operation,
            String failureMessage
    ) {
        Objects.requireNonNull(jsonBody, "jsonBody");
        HttpRequest request = request(relativePath)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        jsonBody,
                        StandardCharsets.UTF_8
                ))
                .build();
        try {
            return HTTP_CLIENT.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(
                            StandardCharsets.UTF_8
                    )
            );
        } catch (IOException error) {
            throw unavailable(
                    operation,
                    failureMessage,
                    error
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw unavailable(
                    operation,
                    failureMessage,
                    error
            );
        }
    }

    private CompletionStage<HttpResponse<String>> postEmptyAsync(
            String relativePath,
            String operation,
            String failureMessage
    ) {
        HttpRequest request = request(relativePath)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return HTTP_CLIENT.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        ).handle((response, failure) -> {
            if (failure != null) {
                throw unavailable(
                        operation,
                        failureMessage,
                        unwrap(failure)
                );
            }
            return response;
        });
    }

    private String encodeConsumeRequest(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "consume limit must be positive"
            );
        }
        return Jsons.toJson(limit);
    }

    private Map<String, DeliveryCommand> decodeConsumeResponse(
            String value,
            int limit
    ) {
        try {
            Map<String, Object> commands = Jsons.parseObject(value);
            if (commands.size() > limit) {
                throw malformedCommand("Delivery Command batch size");
            }
            Map<String, DeliveryCommand> decoded = new LinkedHashMap<>();
            commands.forEach((entryKey, encoded) -> {
                if (entryKey.isBlank() || !(encoded instanceof Map<?, ?>)) {
                    throw malformedCommand("Delivery Command entry key");
                }
                DeliveryCommand command = codec.decodeDeliveryCommand(
                        Jsons.toJson(encoded)
                );
                if (command == null) {
                    throw malformedCommand("Delivery Command envelope");
                }
                decoded.put(entryKey, command);
            });
            return Collections.unmodifiableMap(decoded);
        } catch (WorkerDeliveryAdapterException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            throw new WorkerDeliveryAdapterException(
                    WorkerDeliveryAdapterErrorCode.REMOTE_API_PROTOCOL_ERROR,
                    COMMAND_DECODE_OPERATION,
                    "Delivery Command consume response is malformed",
                    error
            );
        }
    }

    private String encodeResultBatch(
            List<String> encodedDeliveryReports
    ) {
        if (encodedDeliveryReports == null
                || encodedDeliveryReports.isEmpty()
                || encodedDeliveryReports.size() > MAX_RESULTS_PER_APPEND) {
            throw protocolFailure(
                    REPORT_ENCODE_OPERATION,
                    "DeliveryReport batch must contain 1..100 results"
            );
        }
        for (String encodedDeliveryReport : encodedDeliveryReports) {
            if (encodedDeliveryReport == null
                    || encodedDeliveryReport.isEmpty()) {
                throw protocolFailure(
                        REPORT_ENCODE_OPERATION,
                        "Encoded DeliveryReport must be non-empty"
                );
            }
        }
        return Jsons.toJson(encodedDeliveryReports);
    }

    private void requireCompleteResultResponse(
            String value,
            int expectedCount
    ) {
        try {
            Map<String, Object> payload = Jsons.parseObject(value);
            if (!payload.keySet().equals(APPEND_FIELDS)
                    || !(payload.get("acceptedCount")
                    instanceof Long accepted)
                    || !(payload.get("rejectedCount")
                    instanceof Long rejected)
                    || accepted < 0
                    || rejected < 0
                    || accepted > Integer.MAX_VALUE
                    || rejected > Integer.MAX_VALUE
                    || accepted + rejected != expectedCount) {
                throw malformedReport("DeliveryReport append response");
            }
        } catch (WorkerDeliveryAdapterException error) {
            throw error;
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new WorkerDeliveryAdapterException(
                    WorkerDeliveryAdapterErrorCode.REMOTE_API_PROTOCOL_ERROR,
                    REPORT_DECODE_OPERATION,
                    "DeliveryReport append response is malformed",
                    error
            );
        }
    }

    private HttpRequest.Builder request(String relativePath) {
        URI uri = URI.create(baseUrl + relativePath);
        return HttpRequest.newBuilder(uri).timeout(requestTimeout);
    }

    private static String commandPath(String adapterId) {
        return "/api/v1/worker-delivery/endpoint-managers/"
                + encodePathSegment(adapterId)
                + "/commands:consume";
    }

    private static String reportPath(String adapterId) {
        return "/api/v1/worker-delivery/endpoint-managers/"
                + encodePathSegment(adapterId)
                + "/results:append";
    }

    private static String routePath(
            String adapterId,
            String workerId
    ) {
        return "/api/v1/worker-delivery/endpoint-managers/"
                + encodePathSegment(adapterId)
                + "/workers/"
                + encodePathSegment(workerId)
                + ":verify-binding";
    }

    private static String responseBody(HttpResponse<String> response) {
        return response.body() == null ? "" : response.body();
    }

    private static WorkerDeliveryAdapterException malformedCommand(
            String type
    ) {
        return new WorkerDeliveryAdapterException(
                WorkerDeliveryAdapterErrorCode.REMOTE_API_PROTOCOL_ERROR,
                COMMAND_DECODE_OPERATION,
                type + " is malformed",
                null
        );
    }

    private static WorkerDeliveryAdapterException malformedReport(
            String type
    ) {
        return protocolFailure(
                REPORT_DECODE_OPERATION,
                type + " is malformed"
        );
    }

    private static WorkerDeliveryAdapterException protocolFailure(
            String operation,
            String message
    ) {
        return new WorkerDeliveryAdapterException(
                WorkerDeliveryAdapterErrorCode.REMOTE_API_PROTOCOL_ERROR,
                operation,
                message,
                null
        );
    }

    private static WorkerDeliveryAdapterException commandStatusFailure(
            int statusCode
    ) {
        WorkerDeliveryAdapterErrorCode errorCode = statusCode >= 500
                ? WorkerDeliveryAdapterErrorCode.REMOTE_API_UNAVAILABLE
                : WorkerDeliveryAdapterErrorCode.REMOTE_API_PROTOCOL_ERROR;
        return new WorkerDeliveryAdapterException(
                errorCode,
                COMMAND_OPERATION,
                "Delivery Command consume failed with HTTP " + statusCode,
                null
        );
    }

    private static WorkerDeliveryAdapterException reportStatusFailure(
            int statusCode
    ) {
        WorkerDeliveryAdapterErrorCode errorCode = statusCode >= 500
                ? WorkerDeliveryAdapterErrorCode.REMOTE_API_UNAVAILABLE
                : WorkerDeliveryAdapterErrorCode.REMOTE_API_PROTOCOL_ERROR;
        return new WorkerDeliveryAdapterException(
                errorCode,
                REPORT_OPERATION,
                "DeliveryReport append failed with HTTP " + statusCode,
                null
        );
    }

    private static WorkerDeliveryAdapterException routeStatusFailure(
            int statusCode
    ) {
        WorkerDeliveryAdapterErrorCode errorCode;
        if (statusCode >= 400 && statusCode < 500) {
            errorCode = WorkerDeliveryAdapterErrorCode
                    .WORKER_ROUTE_REJECTED;
        } else if (statusCode >= 500) {
            errorCode = WorkerDeliveryAdapterErrorCode
                    .REMOTE_API_UNAVAILABLE;
        } else {
            errorCode = WorkerDeliveryAdapterErrorCode
                    .REMOTE_API_PROTOCOL_ERROR;
        }
        return new WorkerDeliveryAdapterException(
                errorCode,
                ROUTE_OPERATION,
                "Worker route verification failed with HTTP " + statusCode,
                null
        );
    }

    private static WorkerDeliveryAdapterException unavailable(
            String operation,
            String message,
            Throwable cause
    ) {
        return new WorkerDeliveryAdapterException(
                WorkerDeliveryAdapterErrorCode.REMOTE_API_UNAVAILABLE,
                operation,
                message,
                cause
        );
    }

    private static String encodePathSegment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "path segment must be non-blank"
            );
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static String trimTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private static URI requireBaseUrl(URI value) {
        Objects.requireNonNull(value, "baseUrl");
        String scheme = value.getScheme();
        if (!value.isAbsolute()
                || value.getHost() == null
                || value.getRawQuery() != null
                || value.getRawFragment() != null
                || !("http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException(
                    "baseUrl must be an absolute HTTP(S) URI without "
                            + "query or fragment"
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

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

}
