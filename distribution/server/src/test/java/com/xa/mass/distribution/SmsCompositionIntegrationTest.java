package com.xa.mass.distribution;

import com.xa.mass.server.XaMassServerConfiguration;
import com.xa.mass.sms.backend.SmsProductConfiguration;
import com.xa.mass.sms.backend.ListenerService;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.call.TaskCallSubmissionService;
import com.xa.mass.server.worker.group.WorkerGroupRegistrationService;

import com.xa.mass.kernel.pacer.KernelPacerRuntime;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.worker.execution.WorkerOutcomeReporter;
import com.xa.mass.worker.javase.JavaWorkerManager;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterManager;
import com.xa.mass.workerdelivery.json.Jsons;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("sms-composition")
class SmsCompositionIntegrationTest {
    @Test @Timeout(90)
    void productTraversesRealWorkerWhileTaskHttpRoutesAreBlocked() throws Exception {
        String scope = "test_sms_" + UUID.randomUUID().toString().replace("-", "");
        String redisUrl = System.getenv().getOrDefault("XA_MASS_REDIS_URL", "redis://127.0.0.1:6379/15");
        int serverPort = port(), adapterPort = port();
        URI base = URI.create("http://127.0.0.1:" + serverPort);
        var app = new SpringApplication(XaMassServerConfiguration.class, SmsProductConfiguration.class, ProductWorkerConfiguration.class, ConsoleFrontendConfiguration.class, BlockTaskHttp.class);
        app.setRegisterShutdownHook(false);
        try (var context = app.run(
                "--spring.profiles.active=product-preview,sms-reception", "--server.port=" + serverPort,
                "--spring.config.additional-location=" + java.nio.file.Path.of("../product-preview/config/application-product-preview.yaml").toAbsolutePath().normalize().toUri(),
                "--xa.mass.redis.url=" + redisUrl, "--xa.mass.redis.scope=" + scope,
                "--xa.mass.worker-delivery.adapter.remote-base-url=" + base,
                "--xa.mass.worker-delivery.adapter.instances.products-websocket.listen-port=" + adapterPort,
                "--xa.mass.worker-endpoints.endpoints.products-websocket.public-uri=ws://127.0.0.1:"
                        + adapterPort + "/api/v1/worker-delivery/websocket",
                "--spring.web.resources.static-locations=" + java.nio.file.Path.of(System.getProperty("xa.mass.test.frontend")).toUri(),
                "--logging.level.root=WARN");
             var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            assertThat(context.getBeansOfType(KernelPacerRuntime.class)).hasSize(1);
            assertThat(context.getBeansOfType(WorkerDeliveryAdapterManager.class)).hasSize(1);
            assertThat(context.getBeansOfType(ListenerService.class)).hasSize(1);
            for (Class<?> type : List.of(RedisClient.class, TaskRuntime.class, TaskResourceCatalog.class,
                    WorkerResourceCatalog.class, WorkerScoreCore.class, TaskDataService.class,
                    TaskCallSubmissionService.class, WorkerGroupRegistrationService.class)) {
                assertThat(context.getBeansOfType(type)).as("One shared %s", type.getSimpleName()).hasSize(1);
            }
            assertThat(send(http, base, "/api/v1/tasks/blocked/items:call", Map.of()).statusCode()).isEqualTo(503);
            assertThat(send(http, base, "/api/v1/tasks/blocked/results:load", List.of("x")).statusCode()).isEqualTo(503);
            assertThat(send(http, base, "/api/v1/catalog", null).statusCode()).isEqualTo(404);
            assertThat(send(http, base, "/api/simulation/metrics", null).statusCode()).isEqualTo(404);
            assertThat(send(http, base, "/api/v1/sms/missing", null).statusCode()).isEqualTo(404);
            for (String path : List.of("/sms", "/sms/", "/sms/listeners", "/sms/listeners/", "/sms/metrics", "/sms/metrics/")) {
                var page = send(http, base, path, null);
                assertThat(page.statusCode()).isEqualTo(200);
                assertThat(page.body()).contains("/static/js/").doesNotContain("/sms/assets/");
                assertThat(page.body()).isEqualTo(send(http, base, "/", null).body());
            }
            assertThat(Jsons.parseObject(send(http, base, "/v3/api-docs", null).body()).get("paths").toString())
                    .contains("/api/v1/sms/listeners", "/api/v1/tasks/{taskId}/results:load");

            var managerRef = new AtomicReference<JavaWorkerManager>();
            var reporter = new AtomicReference<WorkerOutcomeReporter>();
            var baseline = new AtomicReference<Map<String, Object>>();
            var handler = WorkerEventDefinition.extension("sms.listen.start", WorkerEventParameterResolvers.jsonMap(),
                    (request, outcome) -> {
                        long now = System.currentTimeMillis();
                        Map<String, Object> snapshot = Map.of("listenerId", request.get("listenerId"),
                                "applicationId", "A", "country", "CN", "phone", "+861700000001",
                                "workerId", managerRef.get().snapshot("one").workerId(),
                                "startedAt", now, "expiresAt", now + 60_000, "status", "LISTENING");
                        baseline.set(snapshot);
                        reporter.set(outcome);
                        return Jsons.toJson(snapshot);
                    });
            try (var manager = JavaWorkerManager.builder(base, "demo-sim", WorkerTransportType.WEBSOCKET)
                    .replica("one", () -> Map.of("phone", "+861700000001", "country", "CN"), List.of(handler))
                    .build()) {
                managerRef.set(manager);
                manager.start();
                var created = send(http, base, "/api/v1/sms/listeners", Map.of("requestId", "no-http",
                        "applicationId", "A", "country", "CN", "listenSeconds", 60));
                assertThat(created.statusCode()).isEqualTo(200);
                String id = (String) Jsons.parseObject(created.body()).get("id");
                awaitStatus(http, base, id, "LISTENING");
                var received = new LinkedHashMap<>(baseline.get());
                received.put("status", "RECEIVED");
                received.put("sms", Map.of("smsId", "proof-sms", "text", "[A] 123456", "code", "123456",
                        "templateId", "A-code", "receivedAt", System.currentTimeMillis()));
                assertThat(reporter.get().report(9, System.currentTimeMillis(), Jsons.toJson(received))).isTrue();
                Map<String, Object> observed = awaitStatus(http, base, id, "RECEIVED");
                assertThat(observed).containsEntry("phone", "+861700000001").containsKey("sms");
                reporter.set(null);
            }
        } finally {
            cleanup(redisUrl, scope);
        }
    }

    @Test @Timeout(60)
    void ordinaryProfileServesConsoleWithoutSmsApiGroupsOrJobs() throws Exception {
        String scope = "test_sms_" + UUID.randomUUID().toString().replace("-", "");
        String redisUrl = System.getenv().getOrDefault("XA_MASS_REDIS_URL", "redis://127.0.0.1:6379/15");
        int serverPort = port();
        URI base = URI.create("http://127.0.0.1:" + serverPort);
        var app = new SpringApplication(XaMassServerConfiguration.class, SmsProductConfiguration.class, ProductWorkerConfiguration.class, ConsoleFrontendConfiguration.class);
        app.setRegisterShutdownHook(false);
        try (var context = app.run("--spring.profiles.active=default", "--server.port=" + serverPort,
                "--xa.mass.redis.url=" + redisUrl, "--xa.mass.redis.scope=" + scope,
                "--xa.mass.worker-assembly.group-config-json={}", "--spring.web.resources.static-locations=" + java.nio.file.Path.of(System.getProperty("xa.mass.test.frontend")).toUri(),
                "--logging.level.root=WARN");
             var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            assertThat(context.getBeansOfType(ListenerService.class)).isEmpty();
            assertThat(context.getBean(WorkerResourceCatalog.class)
                    .getWorkerGroupDescriptors(List.of("demo-sim")).values())
                    .allMatch(value -> value == null);
            for (String path : List.of("/api/v1/sms/catalog", "/api/v1/sms/listeners", "/sms/index.html", "/sms/assets/missing.js", "/sms/missing", "/api/v1/sms/missing")) {
                assertThat(send(http, base, path, null).statusCode()).as(path).isEqualTo(404);
            }
            for (String path : List.of("/sms", "/sms/", "/sms/listeners", "/sms/listeners/", "/sms/metrics", "/sms/metrics/")) {
                var page = send(http, base, path, null);
                assertThat(page.statusCode()).as(path).isEqualTo(200);
                assertThat(page.body()).contains("/static/js/").doesNotContain("/sms/assets/");
                assertThat(page.body()).isEqualTo(send(http, base, "/", null).body());
            }
            assertThat(send(http, base, "/v3/api-docs", null).body()).doesNotContain("/api/v1/sms/");
        } finally {
            cleanup(redisUrl, scope);
        }
    }

    private static Map<String, Object> awaitStatus(HttpClient http, URI base, String id, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        Map<String, Object> record = Map.of();
        do {
            record = Jsons.parseObject(send(http, base, "/api/v1/sms/listeners/" + id, null).body());
            if (expected.equals(record.get("status"))) return record;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        assertThat(record.get("status")).isEqualTo(expected);
        return record;
    }

    private static HttpResponse<String> send(HttpClient http, URI base, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(3));
        if (body != null) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Jsons.toJson(body)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static int port() throws Exception { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private static void cleanup(String redisUrl, String scope) {
        if (!scope.matches("test_sms_[0-9a-f]{32}")) throw new IllegalArgumentException("Unsafe proof scope");
        String prefix = "xa_mass:" + scope + ":";
        RedisClient client = RedisClient.create(redisUrl);
        try (var connection = client.connect()) {
            var commands = connection.sync();
            ScanCursor cursor = ScanCursor.INITIAL;
            do {
                var page = commands.scan(cursor, ScanArgs.Builder.matches(prefix + "*").limit(1000));
                for (String key : page.getKeys()) assertThat(key).startsWith(prefix);
                if (!page.getKeys().isEmpty()) commands.unlink(page.getKeys().toArray(String[]::new));
                cursor = page;
            } while (!cursor.isFinished());
        } finally {
            client.shutdown(Duration.ZERO, Duration.ofSeconds(3));
        }
    }
    @Configuration(proxyBeanMethods = false)
    static class BlockTaskHttp {
        @Bean FilterRegistrationBean<jakarta.servlet.Filter> blockedTaskRoutes() {
            var registration = new FilterRegistrationBean<jakarta.servlet.Filter>();
            registration.setFilter((request, response, chain) -> {
                String path = ((HttpServletRequest) request).getRequestURI();
                if (path.startsWith("/api/v1/tasks/") && (path.endsWith("/items:call") || path.endsWith("/results:load"))) {
                    ((HttpServletResponse) response).setStatus(503);
                    response.getWriter().write("{}");
                } else chain.doFilter(request, response);
            });
            return registration;
        }
    }
}
