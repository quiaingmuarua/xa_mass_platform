package com.xa.mass.worker.transport.polling;

import com.xa.mass.worker.execution.WorkerCommandProcessor;
import com.xa.mass.worker.transport.WorkerTransportException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

public final class PollingWorkerTransport {

    private final HttpClient http;
    private final URI pollUri;
    private final URI resultUri;
    private final Duration requestTimeout;
    private final WorkerDeliveryCodec codec;
    private final WorkerCommandProcessor processor;
    private SeedResult pendingResult;

    public PollingWorkerTransport(
            URI serverUrl,
            String endpointManagerId,
            String workerId,
            Duration requestTimeout,
            WorkerDeliveryCodec codec,
            WorkerCommandProcessor processor
    ) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(requestTimeout)
                        .build(),
                serverUrl,
                endpointManagerId,
                workerId,
                requestTimeout,
                codec,
                processor
        );
    }

    PollingWorkerTransport(
            HttpClient http,
            URI serverUrl,
            String endpointManagerId,
            String workerId,
            Duration requestTimeout,
            WorkerDeliveryCodec codec,
            WorkerCommandProcessor processor
    ) {
        this.http = http;
        this.requestTimeout = requestTimeout;
        this.codec = codec;
        this.processor = processor;
        String base = trimTrailingSlash(serverUrl.toString());
        String workerPath = "/api/v1/worker-delivery/endpoint-managers/"
                + encodePathSegment(endpointManagerId)
                + "/workers/"
                + encodePathSegment(workerId)
                + "/";
        pollUri = URI.create(base + workerPath + "commands:poll");
        resultUri = URI.create(base + workerPath + "results");
    }

    public boolean runOnce() throws IOException, InterruptedException {
        if (pendingResult != null) {
            submitPendingResult();
            return true;
        }

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(pollUri)
                        .timeout(requestTimeout)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        if (response.statusCode() == 204) {
            return false;
        }
        if (response.statusCode() != 200) {
            throw new WorkerTransportException(
                    "Worker command poll failed with HTTP "
                            + response.statusCode()
            );
        }
        WorkerCommandEnvelope command = codec.decodeWorkerCommand(
                response.body()
        );
        if (command == null) {
            throw new WorkerTransportException(
                    "Worker command response is malformed"
            );
        }
        Optional<SeedResult> result = processor.process(command);
        if (result.isEmpty()) {
            return false;
        }
        pendingResult = result.orElseThrow();
        submitPendingResult();
        return true;
    }

    public void runForever(Duration pollInterval) throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                boolean handled = runOnce();
                if (!handled) {
                    Thread.sleep(pollInterval);
                }
            } catch (IOException | WorkerTransportException error) {
                System.getLogger(getClass().getName()).log(
                        System.Logger.Level.WARNING,
                        error.getMessage()
                );
                Thread.sleep(pollInterval);
            }
        }
    }

    boolean hasPendingResult() {
        return pendingResult != null;
    }

    URI pollUri() {
        return pollUri;
    }

    URI resultUri() {
        return resultUri;
    }

    private void submitPendingResult()
            throws IOException, InterruptedException {
        SeedResult result = pendingResult;
        HttpResponse<Void> response = http.send(
                HttpRequest.newBuilder(resultUri)
                        .timeout(requestTimeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                codec.encodeSeedResult(result),
                                StandardCharsets.UTF_8
                        ))
                        .build(),
                HttpResponse.BodyHandlers.discarding()
        );
        if (response.statusCode() != 202) {
            throw new WorkerTransportException(
                    "Worker result append failed with HTTP "
                            + response.statusCode()
            );
        }
        if (pendingResult == result) {
            pendingResult = null;
        }
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
