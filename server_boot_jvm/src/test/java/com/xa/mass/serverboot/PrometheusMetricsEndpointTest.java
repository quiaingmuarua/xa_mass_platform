package com.xa.mass.serverboot;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DirtiesContext
@SpringBootTest(
        classes = ServerBootConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "xa.mass.kernel-pacer.enabled=false",
                "xa.mass.task-retention.enabled=false",
                "xa.mass.redis.url=redis://127.0.0.1:1/15",
                "xa.mass.redis.scope=test_prometheus_endpoint"
        }
)
class PrometheusMetricsEndpointTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void registerHistoryDatabase(DynamicPropertyRegistry registry) {
        registry.add("xa.mass.task-retention.database-path", () -> tempDir.resolve("task-history.sqlite").toString());
    }

    @LocalServerPort
    private int port;

    @Test
    void exposesPrometheusMetricsWithHttpApiErrorsAndRuntimeGauges() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        // Trigger a 401 via AccessKeyFilter and a 400 via ApiExceptionHandler so error & HTTP meters materialize.
        HttpResponse<String> unauthenticated = client.send(
                HttpRequest.newBuilder(endpoint("/api/v1/projects"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertThat(unauthenticated.statusCode()).isEqualTo(401);

        HttpResponse<String> malformed = client.send(
                HttpRequest.newBuilder(endpoint("/api/v1/tasks"))
                        .header("Authorization", "Bearer xak_dev_1000")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{invalid", StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertThat(malformed.statusCode()).isEqualTo(400);

        HttpResponse<String> scrape = client.send(
                HttpRequest.newBuilder(endpoint("/actuator/prometheus"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.headers().firstValue("Content-Type").orElse("")).contains("text/plain");
        String body = scrape.body();
        assertThat(body)
                .contains("application=\"xa-mass-server\"")
                .contains("http_server_requests_seconds_bucket")
                .contains("xa_mass_pacer_running")
                .contains("xa_mass_rpc_waiters_active")
                .contains("xa_mass_rpc_observations_pending")
                .contains("xa_mass_direct_call_targets_pending")
                .contains("xa_mass_direct_call_batches_active")
                .contains("xa_mass_api_errors_total")
                .contains("error=\"UNAUTHENTICATED\"")
                .contains("error=\"MALFORMED_REQUEST\"");
    }

    private URI endpoint(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
