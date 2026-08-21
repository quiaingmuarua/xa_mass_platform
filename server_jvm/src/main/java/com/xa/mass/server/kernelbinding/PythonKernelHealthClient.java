package com.xa.mass.server.kernelbinding;

import java.util.Map;
import org.springframework.web.client.RestClient;

final class PythonKernelHealthClient {

    private final RestClient restClient;

    PythonKernelHealthClient(RestClient restClient) {
        this.restClient = restClient;
    }

    boolean isHealthy() {
        try {
            Map<?, ?> body = restClient.get()
                    .uri("/health")
                    .retrieve()
                    .body(Map.class);
            return body != null && "ok".equals(body.get("status"));
        } catch (RuntimeException error) {
            return false;
        }
    }
}
