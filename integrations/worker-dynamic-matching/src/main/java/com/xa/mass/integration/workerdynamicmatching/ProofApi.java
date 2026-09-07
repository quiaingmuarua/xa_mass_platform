package com.xa.mass.integration.workerdynamicmatching;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ProofApi implements AutoCloseable {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final URI base;
    ProofApi(String base) { this.base = URI.create(base); }

    Map<String, Object> call(String method, String path, Object body, boolean observation) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(2))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(Jsons.toJson(body), StandardCharsets.UTF_8)).build();
        HttpResponse<String> response;
        try { response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
        catch (IOException error) {
            if (observation) throw new TemporaryRead();
            throw new ProofFailure("mutation-result-unknown");
        }
        if (observation && Set.of(429, 502, 503, 504).contains(response.statusCode())) throw new TemporaryRead();
        require(response.statusCode() == 200, "http-status-" + response.statusCode());
        return Jsons.parseObject(response.body());
    }

    static void require(boolean value, String code) { if (!value) throw new ProofFailure(code); }
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        require(value instanceof Map<?, ?>, "object-shape");
        return (Map<String, Object>) value;
    }
    static List<?> array(Object value) { require(value instanceof List<?>, "array-shape"); return (List<?>) value; }
    static String text(Object value) { require(value instanceof String, "string-shape"); return (String) value; }
    static long number(Object value) { require(value instanceof Number, "number-shape"); return ((Number) value).longValue(); }
    static Map<String, String> strings(Object value) {
        var result = new java.util.LinkedHashMap<String, String>();
        object(value).forEach((key, child) -> result.put(key, text(child)));
        return Map.copyOf(result);
    }
    @Override public void close() { http.close(); }
    static final class ProofFailure extends IllegalStateException {
        final String code;
        ProofFailure(String code) { super(code); this.code = code; }
    }
    static final class TemporaryRead extends Exception { }
}
