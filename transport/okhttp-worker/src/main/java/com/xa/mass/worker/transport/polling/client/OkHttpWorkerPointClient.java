package com.xa.mass.worker.transport.polling.client;

import com.xa.mass.transport.client.WorkerPointClient;
import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class OkHttpWorkerPointClient
        implements WorkerPointClient {

    private static final MediaType JSON =
            MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final HttpUrl pollUrl;
    private final HttpUrl resultUrl;
    private volatile boolean closed;
    private volatile Call activeCall;

    public OkHttpWorkerPointClient(
            URI serverUrl,
            String endpointManagerId,
            String workerId,
            Duration requestTimeout
    ) {
        requireNonBlank(endpointManagerId, "endpointManagerId");
        requireNonBlank(workerId, "workerId");
        http = client(requestTimeout);
        HttpUrl workerBase = httpUrl(serverUrl)
                .newBuilder()
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

    @Override
    public Optional<String> pollCommand() throws IOException {
        Request request = new Request.Builder()
                .url(pollUrl)
                .post(RequestBody.create(new byte[0]))
                .build();
        try (Response response = execute(request)) {
            if (response.code() == 204) {
                return Optional.empty();
            }
            if (response.code() != 200 || response.body() == null) {
                throw new WorkerException(
                        WorkerErrorCode.COMMAND_POLL_FAILED,
                        "polling.pollCommand",
                        "Worker command poll failed with HTTP "
                                + response.code(),
                        null
                );
            }
            return Optional.of(response.body().string());
        }
    }

    @Override
    public void submitResult(String encodedResult) throws IOException {
        if (encodedResult == null) {
            throw new IllegalArgumentException(
                    "encodedResult must be present"
            );
        }
        Request request = new Request.Builder()
                .url(resultUrl)
                .post(RequestBody.create(encodedResult, JSON))
                .build();
        try (Response response = execute(request)) {
            if (response.code() != 202) {
                throw new WorkerException(
                        WorkerErrorCode.RESULT_SUBMIT_FAILED,
                        "polling.submitResult",
                        "Worker result append failed with HTTP "
                                + response.code(),
                        null
                );
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
                    "OkHttpWorkerPointClient is closed"
            );
        }
    }

    private static OkHttpClient client(Duration timeout) {
        long millis = requirePositive(
                timeout,
                "requestTimeout"
        ).toMillis();
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
            throw new IllegalArgumentException(
                    name + " must be non-blank"
            );
        }
    }
}
