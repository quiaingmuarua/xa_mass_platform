package com.xa.mass.worker.android;

import com.xa.mass.transport.client.WorkerControlClient;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

final class AndroidOkHttpWorkerControlClient
        implements WorkerControlClient {

    private static final MediaType JSON =
            MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http = new OkHttpClient();
    private final HttpUrl runtimeApiBaseUrl;
    private volatile boolean closed;

    AndroidOkHttpWorkerControlClient(URI runtimeApiBaseUrl) {
        this.runtimeApiBaseUrl = requireHttpUrl(runtimeApiBaseUrl);
    }

    @Override
    public String register(
            String workerGroupId,
            String clientWorkerKey,
            Duration timeout
    ) throws IOException {
        requireOpen();
        HttpUrl url = workerGroupBase(
                requireNonBlank(workerGroupId, "workerGroupId")
        ).newBuilder().addPathSegment("workers:register").build();
        Map<String, Object> response = executeObject(
                url,
                Map.of(
                        "clientWorkerKey",
                        requireNonBlank(
                                clientWorkerKey,
                                "clientWorkerKey"
                        )
                ),
                timeout,
                "workerControl.register"
        );
        if (!response.keySet().equals(Set.of("workerId"))
                || !(response.get("workerId") instanceof String)) {
            throw failure(
                    "workerControl.register",
                    "Worker registration response is invalid",
                    null
            );
        }
        return requireCanonicalWorkerId(
                (String) response.get("workerId")
        );
    }

    @Override
    public URI bind(
            String workerGroupId,
            String clientWorkerKey,
            String workerId,
            WorkerTransportType transportType,
            Map<String, Object> workerProperties,
            Duration timeout
    ) throws IOException {
        requireOpen();
        String group = requireNonBlank(workerGroupId, "workerGroupId");
        String clientKey = requireNonBlank(
                clientWorkerKey,
                "clientWorkerKey"
        );
        String canonicalWorkerId = requireCanonicalWorkerId(workerId);
        if (transportType == null) {
            throw new IllegalArgumentException(
                    "transportType must be present"
            );
        }
        if (workerProperties == null) {
            throw new IllegalArgumentException(
                    "workerProperties must be present"
            );
        }
        HttpUrl url = workerGroupBase(group).newBuilder()
                .addPathSegment("workers")
                .addPathSegment(canonicalWorkerId + ":bind")
                .build();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientWorkerKey", clientKey);
        body.put("transportType", transportType.name());
        body.put("workerProperties", workerProperties);
        Map<String, Object> response = executeObject(
                url,
                body,
                timeout,
                "workerControl.bind"
        );
        if (!response.keySet().equals(Set.of(
                "transportType",
                "endpointUri"
        ))
                || !(response.get("transportType") instanceof String)
                || !(response.get("endpointUri") instanceof String)
                || !transportType.name().equals(
                        response.get("transportType")
                )) {
            throw failure(
                    "workerControl.bind",
                    "Worker binding response is invalid",
                    null
            );
        }
        return requireEndpointUri(
                (String) response.get("endpointUri"),
                transportType
        );
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        http.dispatcher().cancelAll();
        http.connectionPool().evictAll();
        http.dispatcher().executorService().shutdownNow();
    }

    private Map<String, Object> executeObject(
            HttpUrl url,
            Map<String, Object> body,
            Duration timeout,
            String operation
    ) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(Jsons.toJson(body), JSON))
                .build();
        Call call = http.newCall(request);
        call.timeout().timeout(
                requirePositive(timeout).toMillis(),
                TimeUnit.MILLISECONDS
        );
        try (Response response = call.execute()) {
            if (response.code() != 200 || response.body() == null) {
                throw failure(
                        operation,
                        "Worker control request failed with HTTP "
                                + response.code(),
                        null
                );
            }
            try {
                return Jsons.parseObject(response.body().string());
            } catch (IllegalArgumentException error) {
                throw failure(
                        operation,
                        "Worker control response is invalid",
                        error
                );
            }
        }
    }

    private HttpUrl workerGroupBase(String workerGroupId) {
        return runtimeApiBaseUrl.newBuilder()
                .addPathSegment("api")
                .addPathSegment("v1")
                .addPathSegment("worker-groups")
                .addPathSegment(workerGroupId)
                .build();
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Android Worker control client is closed"
            );
        }
    }

    private static URI requireEndpointUri(
            String encoded,
            WorkerTransportType transportType
    ) {
        URI uri;
        try {
            uri = URI.create(encoded);
        } catch (IllegalArgumentException error) {
            throw failure(
                    "workerControl.bind",
                    "Worker binding response contains an invalid endpointUri",
                    error
            );
        }
        String scheme = uri.getScheme();
        boolean valid = uri.isAbsolute() && uri.getHost() != null;
        if (transportType == WorkerTransportType.POLLING) {
            valid = valid && ("http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme));
        } else if (transportType == WorkerTransportType.WEBSOCKET) {
            valid = valid && ("ws".equalsIgnoreCase(scheme)
                    || "wss".equalsIgnoreCase(scheme));
        } else {
            valid = valid && "tcp".equalsIgnoreCase(scheme);
        }
        if (!valid) {
            throw failure(
                    "workerControl.bind",
                    "Worker binding response contains an endpointUri "
                            + "incompatible with " + transportType,
                    null
            );
        }
        return uri;
    }

    private static String requireCanonicalWorkerId(String workerId) {
        String value = requireNonBlank(workerId, "workerId");
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException error) {
            throw failure(
                    "workerControl.workerId",
                    "workerId must be a canonical UUID",
                    error
            );
        }
        return value;
    }

    private static HttpUrl requireHttpUrl(URI value) {
        if (value == null
                || (!"http".equalsIgnoreCase(value.getScheme())
                && !"https".equalsIgnoreCase(value.getScheme()))) {
            throw new IllegalArgumentException(
                    "runtimeApiBaseUrl must use HTTP or HTTPS"
            );
        }
        HttpUrl parsed = HttpUrl.parse(value.toString());
        if (parsed == null) {
            throw new IllegalArgumentException(
                    "runtimeApiBaseUrl must be an absolute HTTP(S) URI"
            );
        }
        return parsed;
    }

    private static Duration requirePositive(Duration value) {
        if (value == null
                || value.isZero()
                || value.isNegative()
                || value.toMillis() <= 0) {
            throw new IllegalArgumentException(
                    "requestTimeout must be positive"
            );
        }
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }

    private static WorkerException failure(
            String operation,
            String message,
            Throwable cause
    ) {
        return new WorkerException(
                WorkerErrorCode.WORKER_CONTROL_FAILED,
                operation,
                message,
                cause
        );
    }
}
