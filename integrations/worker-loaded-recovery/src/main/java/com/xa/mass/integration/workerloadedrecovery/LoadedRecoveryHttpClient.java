package com.xa.mass.integration.workerloadedrecovery;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

final class LoadedRecoveryHttpClient {

    private final URI baseUri;
    private final Duration timeout;
    private final HttpClient client;

    LoadedRecoveryHttpClient(URI baseUri, Duration timeout) {
        this.baseUri = requireBaseUri(baseUri);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.timeout = timeout;
        client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    JsonResponse json(String method, String path, Object body) {
        BinaryResponse response = binary(method, path, body);
        Map<String, Object> decoded = response.body().length == 0
                ? Map.of()
                : Jsons.parseObject(new String(
                        response.body(),
                        StandardCharsets.UTF_8
                ));
        return new JsonResponse(response.statusCode(), decoded);
    }

    BinaryResponse binary(String method, String path, Object body) {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(
                        Jsons.toJson(body),
                        StandardCharsets.UTF_8
                );
        HttpRequest request = HttpRequest.newBuilder(endpoint(path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .method(method, publisher)
                .build();
        try {
            HttpResponse<byte[]> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofByteArray()
            );
            return new BinaryResponse(
                    response.statusCode(),
                    response.body()
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP request was interrupted", error);
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException("HTTP request failed", error);
        }
    }

    private URI endpoint(String path) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("path must start with /");
        }
        String base = baseUri.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    private static URI requireBaseUri(URI value) {
        if (value == null
                || !value.isAbsolute()
                || value.getHost() == null
                || value.getQuery() != null
                || value.getFragment() != null
                || (!("http".equalsIgnoreCase(value.getScheme()))
                && !("https".equalsIgnoreCase(value.getScheme())))) {
            throw new IllegalArgumentException(
                    "baseUri must be an absolute HTTP(S) URI"
            );
        }
        return value;
    }

    record JsonResponse(int statusCode, Map<String, Object> body) {
    }

    record BinaryResponse(int statusCode, byte[] body) {
    }
}
