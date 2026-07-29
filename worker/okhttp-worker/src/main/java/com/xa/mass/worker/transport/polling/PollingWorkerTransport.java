package com.xa.mass.worker.transport.polling;

import com.xa.mass.worker.execution.WorkerCommandProcessor;
import com.xa.mass.worker.transport.WorkerTransportException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class PollingWorkerTransport implements AutoCloseable {

    private static final MediaType JSON =
            MediaType.get("application/json; charset=utf-8");
    private static final Logger LOG = Logger.getLogger(
            PollingWorkerTransport.class.getName()
    );

    private final OkHttpClient http;
    private final HttpUrl pollUrl;
    private final HttpUrl resultUrl;
    private final WorkerDeliveryCodec codec;
    private final WorkerCommandProcessor processor;
    private volatile boolean closed;
    private volatile Call activeCall;
    private volatile SeedResult pendingResult;

    public PollingWorkerTransport(
            URI serverUrl,
            String endpointManagerId,
            String workerId,
            Duration requestTimeout,
            WorkerDeliveryCodec codec,
            WorkerCommandProcessor processor
    ) {
        this.http = client(requestTimeout);
        this.codec = requirePresent(codec, "codec");
        this.processor = requirePresent(processor, "processor");
        requireNonBlank(endpointManagerId, "endpointManagerId");
        requireNonBlank(workerId, "workerId");
        HttpUrl base = httpUrl(serverUrl);
        HttpUrl workerBase = base.newBuilder()
                .addPathSegment("api")
                .addPathSegment("v1")
                .addPathSegment("worker-delivery")
                .addPathSegment("endpoint-managers")
                .addPathSegment(endpointManagerId)
                .addPathSegment("workers")
                .addPathSegment(workerId)
                .build();
        pollUrl = workerBase.newBuilder()
                .addPathSegment("commands:poll")
                .build();
        resultUrl = workerBase.newBuilder()
                .addPathSegment("results")
                .build();
    }

    public boolean runOnce() throws IOException, InterruptedException {
        requireOpen();
        SeedResult pending = pendingResult;
        if (pending != null) {
            submitPendingResult(pending);
            return true;
        }

        Request request = new Request.Builder()
                .url(pollUrl)
                .post(RequestBody.create(new byte[0]))
                .build();
        try (Response response = execute(request)) {
            if (response.code() == 204) {
                return false;
            }
            if (response.code() != 200 || response.body() == null) {
                throw new WorkerTransportException(
                        "Worker command poll failed with HTTP "
                                + response.code()
                );
            }
            WorkerCommandEnvelope command = codec.decodeWorkerCommand(
                    response.body().string()
            );
            if (command == null) {
                throw new WorkerTransportException(
                        "Worker command response is malformed"
                );
            }
            Optional<SeedResult> result = processor.process(command);
            if (!result.isPresent()) {
                return false;
            }
            pendingResult = result.get();
        }
        submitPendingResult(pendingResult);
        return true;
    }

    public void runForever(Duration pollInterval)
            throws InterruptedException {
        requirePositive(pollInterval, "pollInterval");
        while (!closed && !Thread.currentThread().isInterrupted()) {
            try {
                boolean handled = runOnce();
                if (!handled && !closed) {
                    Thread.sleep(pollInterval.toMillis());
                }
            } catch (IOException | WorkerTransportException error) {
                if (!closed) {
                    LOG.log(
                            Level.WARNING,
                            error.getMessage()
                    );
                    Thread.sleep(pollInterval.toMillis());
                }
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Call call = activeCall;
        if (call != null) {
            call.cancel();
        }
        http.dispatcher().cancelAll();
        http.connectionPool().evictAll();
        http.dispatcher().executorService().shutdownNow();
    }

    public boolean hasPendingResult() {
        return pendingResult != null;
    }

    public URI pollUri() {
        return pollUrl.uri();
    }

    public URI resultUri() {
        return resultUrl.uri();
    }

    private void submitPendingResult(SeedResult sending)
            throws IOException {
        Request request = new Request.Builder()
                .url(resultUrl)
                .post(RequestBody.create(
                        codec.encodeSeedResult(sending),
                        JSON
                ))
                .build();
        try (Response response = execute(request)) {
            if (response.code() != 202) {
                throw new WorkerTransportException(
                        "Worker result append failed with HTTP "
                                + response.code()
                );
            }
            if (pendingResult == sending) {
                pendingResult = null;
            }
        }
    }

    private Response execute(Request request) throws IOException {
        requireOpen();
        Call call = http.newCall(request);
        activeCall = call;
        try {
            return call.execute();
        } finally {
            if (activeCall == call) {
                activeCall = null;
            }
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "PollingWorkerTransport is closed"
            );
        }
    }

    private static OkHttpClient client(Duration timeout) {
        long millis = requirePositive(timeout, "requestTimeout").toMillis();
        return new OkHttpClient.Builder()
                .connectTimeout(millis, TimeUnit.MILLISECONDS)
                .readTimeout(millis, TimeUnit.MILLISECONDS)
                .writeTimeout(millis, TimeUnit.MILLISECONDS)
                .callTimeout(millis, TimeUnit.MILLISECONDS)
                .build();
    }

    private static HttpUrl httpUrl(URI value) {
        if (value == null
                || (!"http".equalsIgnoreCase(value.getScheme())
                && !"https".equalsIgnoreCase(value.getScheme()))) {
            throw new IllegalArgumentException(
                    "serverUrl must use HTTP or HTTPS"
            );
        }
        HttpUrl parsed = HttpUrl.parse(value.toString());
        if (parsed == null) {
            throw new IllegalArgumentException(
                    "serverUrl must be an absolute HTTP or HTTPS URL"
            );
        }
        return parsed;
    }

    private static Duration requirePositive(
            Duration value,
            String name
    ) {
        if (value == null
                || value.isZero()
                || value.isNegative()
                || value.toMillis() <= 0) {
            throw new IllegalArgumentException(
                    name + " must be positive"
            );
        }
        return value;
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }

    private static <T> T requirePresent(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must be present");
        }
        return value;
    }
}
