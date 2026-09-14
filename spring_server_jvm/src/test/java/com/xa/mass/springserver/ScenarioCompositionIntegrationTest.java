package com.xa.mass.springserver;

import com.xa.mass.scenario.sms.SmsScenarioConfiguration;
import com.xa.mass.scenario.sms.ListenerService;
import com.xa.mass.scenario.messages.MessageCampaignsScenarioConfiguration;
import com.xa.mass.scenario.messages.CampaignService;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.pacer.KernelPacerRuntime;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import static org.assertj.core.api.Assertions.*;

@Tag("scenario-composition")
class ScenarioCompositionIntegrationTest {
    @ParameterizedTest @ValueSource(booleans = {false, true}) @Timeout(90)
    void platformAndPreviewShareOnePlatform(boolean preview) throws Exception {
        boolean sms = preview, messages = preview;
        String scope = "test_products_" + UUID.randomUUID().toString().replace("-", "");
        String redisUrl = System.getenv().getOrDefault("XA_MASS_REDIS_URL", "redis://127.0.0.1:6379/15");
        int port = port(), adapter = port();
        URI base = URI.create("http://127.0.0.1:" + port);
        var application = new SpringApplication(SpringServerConfiguration.class);
        application.setRegisterShutdownHook(false);
        String profiles = preview ? "preview" : "default";
        var arguments = new ArrayList<>(List.of("--spring.profiles.active=" + profiles, "--server.port=" + port,
                "--xa.mass.redis.url=" + redisUrl, "--xa.mass.redis.scope=" + scope,
                "--spring.web.resources.static-locations=" + Path.of(System.getProperty("xa.mass.test.frontend")).toUri(),
                "--logging.level.root=ERROR"));
        if (preview) arguments.addAll(List.of(
                "--xa.mass.worker-delivery.adapter.remote-base-url=" + base,
                "--xa.mass.worker-delivery.adapter.instances.products-websocket.listen-port=" + adapter,
                "--xa.mass.worker-endpoints.endpoints.products-websocket.public-uri=ws://127.0.0.1:" + adapter + "/api/v1/worker-delivery/websocket"));
        try (var context = application.run(arguments.toArray(String[]::new));
             var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            for (Class<?> owner : List.of(KernelPacerRuntime.class, WorkerDeliveryAdapterManager.class, RedisClient.class,
                    TaskRuntime.class, TaskResourceCatalog.class, WorkerResourceCatalog.class, WorkerScoreCore.class))
                assertThat(context.getBeansOfType(owner)).as("one shared %s", owner.getSimpleName()).hasSize(1);
            assertThat(context.getBeansOfType(ListenerService.class)).hasSize(sms ? 1 : 0);
            assertThat(context.getBeansOfType(CampaignService.class)).hasSize(messages ? 1 : 0);
            assertThat(context.getBean(com.xa.mass.server.delivery.adapter.ServerWorkerDeliveryAdapterProperties.class)
                    .instances()).hasSize(preview ? 1 : 0);
            if (sms) assertThat(context.getBean(ListenerService.class).isRunning()).isTrue();
            if (messages) assertThat(context.getBean(CampaignService.class).isRunning()).isTrue();
            var group = context.getBean(WorkerResourceCatalog.class).getWorkerGroupDescriptors(List.of("demo-sim")).get("demo-sim");
            if (sms || messages) {
                assertThat(group).isNotNull();
                if (sms) assertThat(context.getBean(ListenerService.class).catalog().get("countries").toString())
                        .contains("workerGroupId=demo-sim");
                if (messages) assertThat(context.getBean(CampaignService.class).catalog().get("countries").toString())
                        .contains("workerGroupId=demo-sim");
            } else assertThat(group).isNull();
            assertThat(get(client, base, "/api/v1/sms/catalog").statusCode()).isEqualTo(sms ? 200 : 404);
            assertThat(get(client, base, "/api/v1/messages/catalog").statusCode()).isEqualTo(messages ? 200 : 404);
            String openapi = get(client, base, "/v3/api-docs").body();
            assertThat(openapi.contains("/api/v1/sms/catalog")).isEqualTo(preview);
            assertThat(openapi.contains("/api/v1/messages/catalog")).isEqualTo(preview);
            String index = get(client, base, "/").body();
            assertThat(index).contains("/static/js/");
            for (String page : List.of("/sms", "/sms/", "/sms/metrics", "/sms/listeners/", "/messages", "/messages/",
                    "/messages/metrics", "/messages/metrics/", "/messages/campaigns/example", "/messages/campaigns/example/")) {
                var response = get(client, base, page);
                assertThat(response.statusCode()).as(page).isEqualTo(200);
                assertThat(response.body()).isEqualTo(index);
            }
            for (String unknown : List.of("/messages/unknown", "/messages/assets/missing.js", "/messages/campaigns/id/extra",
                    "/api/v1/messages/unknown", "/api/v1/sms/unknown", "/static/missing.js"))
                assertThat(get(client, base, unknown).statusCode()).as(unknown).isEqualTo(404);
        } finally {
            if (!scope.matches("test_products_[0-9a-f]{32}")) throw new IllegalArgumentException("Unsafe scope");
            var client = RedisClient.create(redisUrl);
            try (var connection = client.connect()) {
                ScanCursor cursor = ScanCursor.INITIAL;
                do {
                    var page = connection.sync().scan(cursor, ScanArgs.Builder.matches("xa_mass:" + scope + ":*").limit(1000));
                    if (!page.getKeys().isEmpty()) connection.sync().unlink(page.getKeys().toArray(String[]::new));
                    cursor = page;
                } while (!cursor.isFinished());
            } finally { client.shutdown(Duration.ZERO, Duration.ofSeconds(3)); }
        }
    }
    private static int port() throws Exception { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private static HttpResponse<String> get(HttpClient client, URI base, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
