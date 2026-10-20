package com.xa.mass.serverboot;

import com.xa.mass.scenario.sms.SmsScenarioConfiguration;
import com.xa.mass.scenario.sms.SmsReceptionService;
import com.xa.mass.scenario.messages.MessageCampaignsScenarioConfiguration;
import com.xa.mass.scenario.messages.MessageTaskService;
import com.xa.mass.scenario.appchecks.AppCheckTaskService;
import com.xa.mass.server.worker.observation.WorkerPropertyProjection;
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
    private static final com.xa.mass.server.task.access.TaskCaller CALLER = com.xa.mass.server.testing.TaskCallers.caller("proof", "admin");
    @ParameterizedTest @ValueSource(booleans = {false, true}) @Timeout(90)
    void platformAndPreviewShareOnePlatform(boolean preview) throws Exception {
        boolean sms = preview, messages = preview;
        String scope = "test_products_" + UUID.randomUUID().toString().replace("-", "");
        String redisUrl = System.getenv().getOrDefault("XA_MASS_REDIS_URL", "redis://127.0.0.1:6379/15");
        int port = port(), adapter = port();
        URI base = URI.create("http://127.0.0.1:" + port);
        var application = new SpringApplication(ServerBootConfiguration.class);
        application.setRegisterShutdownHook(false);
        String profiles = preview ? "preview" : "default";
        var arguments = new ArrayList<>(List.of("--spring.profiles.active=" + profiles, "--server.port=" + port,
                "--xa.mass.users.seed=classpath:users/proof-directory.json", "--xa.mass.redis.url=" + redisUrl, "--xa.mass.redis.scope=" + scope,
                "--spring.web.resources.static-locations=" + Path.of(System.getProperty("xa.mass.test.frontend")).toUri(),
                "--logging.level.root=ERROR"));
        var storage = AppCheckConfigurationFixture.properties(10);
        storage.forEach((key, value) -> {
            if (preview || key.startsWith("xa.mass.scenarios.app-checks.")) arguments.add("--" + key + "=" + value);
        });
        if (preview) arguments.addAll(List.of(
                "--xa.mass.worker-delivery.adapter.remote-base-url=" + base,
                "--xa.mass.worker-delivery.adapter.instances.products-websocket.listen-port=" + adapter,
                "--xa.mass.worker-endpoints.endpoints.products-websocket.public-uri=ws://127.0.0.1:" + adapter + "/api/v1/worker-delivery/websocket"));
        try (var context = application.run(arguments.toArray(String[]::new));
             var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            for (Class<?> owner : List.of(KernelPacerRuntime.class, WorkerDeliveryAdapterManager.class, RedisClient.class,
                    TaskRuntime.class, TaskResourceCatalog.class, WorkerResourceCatalog.class, WorkerScoreCore.class))
                assertThat(context.getBeansOfType(owner)).as("one shared %s", owner.getSimpleName()).hasSize(1);
            var directoryHttp = get(client, base, "/api/v1/projects");
            assertThat(directoryHttp.statusCode()).isEqualTo(200);
            if (preview) {
                assertThat(directoryHttp.body()).contains("\"projectId\":\"default\"", "\"projectId\":\"sms\"",
                        "\"projectId\":\"messages\"", "\"projectId\":\"app-checks\"");
                var capabilityHttp = get(client, base, "/api/v1/projects/default/worker-groups");
                assertThat(capabilityHttp.statusCode()).isEqualTo(200);
                assertThat(capabilityHttp.body()).contains("demo-sim", "app-a-sim", "app-b-sim", "workerId", "worker.country", "worker.any");
                var rpc = context.getBean(com.xa.mass.server.task.call.TaskRpcPreparationService.class);
                assertThat(context.getBean(com.xa.mass.server.project.ProjectTaskQueryService.class).list(CALLER, "default", 100, null).tasks()).isEmpty();
                String id = rpc.prepare(CALLER, "raw", "composition-rpc", new com.xa.mass.server.api.v1.contract.task.TaskRpcPrepareRequest(
                        "default", "demo-sim", null, Map.of(), null)).taskId();
                assertThat(get(client, base, "/api/v1/projects/default/tasks/" + id).statusCode()).isEqualTo(200);
                assertThat(get(client, base, "/api/v1/projects/messages/tasks/" + id).statusCode()).isEqualTo(400);
            } else assertThat(directoryHttp.body()).isEqualTo("[]");
            assertThat(context.getBeansOfType(SmsReceptionService.class)).hasSize(sms ? 1 : 0);
            assertThat(context.getBeansOfType(MessageTaskService.class)).hasSize(messages ? 1 : 0);
            assertThat(context.getBeansOfType(AppCheckTaskService.class)).hasSize(preview ? 1 : 0);
            assertThat(context.getBeansOfType(WorkerPropertyProjection.class)).isEmpty();
            assertThat(context.getBean(com.xa.mass.server.worker.observation.WorkerGroupBindings.class).projections()).hasSize(preview ? 1 : 0);
            assertThat(context.getBeansOfType(javax.sql.DataSource.class)).isEmpty();
            assertThat(java.nio.file.Files.exists(Path.of(storage.get("xa.mass.scenarios.app-checks.configuration.path").toString()))).isEqualTo(preview);
            assertThat(java.nio.file.Files.exists(Path.of(storage.get("xa.mass.scenarios.app-checks.configuration.path") + ".lock"))).isEqualTo(preview);
            assertThat(context.getBeansOfType(com.xa.mass.workermatching.QualifiedCountryDefinition.class)).hasSize(preview ? 1 : 0);
            assertThat(context.getBeansOfType(com.xa.mass.server.project.ProjectDefinition.class)).hasSize(preview ? 2 : 0);
            assertThat(context.getBeansOfType(com.xa.mass.server.project.ProjectWorkerRequirements.class)).hasSize(preview ? 2 : 0);
            if (preview) {
                assertThat(context.getBean(com.xa.mass.server.project.ProjectAssemblyProperties.class).projects())
                        .extracting(com.xa.mass.server.project.ProjectDefinition::projectId).containsExactly("default", "sms");
                assertThat(context.getBeansOfType(com.xa.mass.server.project.ProjectDefinition.class).values())
                        .extracting(com.xa.mass.server.project.ProjectDefinition::projectId).containsExactlyInAnyOrder("messages", "app-checks");
                var messageSupply = context.getBean(com.xa.mass.workermatching.QualifiedCountryDefinition.class);
                assertThat(messageSupply).isEqualTo(new com.xa.mass.workermatching.QualifiedCountryDefinition(
                        "messaging", "worker.messaging.available", "worker.messaging.phone", "messaging.enabled", "true", "country"));
                var matching = context.getBean(com.xa.mass.server.worker.observation.WorkerGroupBindings.class);
                for (String group : List.of("app-a-sim", "app-b-sim")) {
                    var definition = matching.definitions().getFirst();
                    assertThat(definition.poolName()).isEqualTo("assignment-window");
                    assertThat(definition.limitsByGroup().get(group).windowMillis()).isEqualTo(60_000);
                    assertThat(definition.limitsByGroup().get(group).maxCount()).isEqualTo(10);
                    assertThat(matching.matchingGroups().get(group).functions()).contains("worker.assignment.available", "worker.any");
                    assertThat(matching.matchingGroups().get(group).pools()).contains("assignment-window", "any");
                }
                assertThat(context.getBean(AppCheckTaskService.class).isRunning()).isTrue();
                var groups = context.getBean(WorkerResourceCatalog.class).getWorkerGroupDescriptors(List.of("app-a-sim", "app-b-sim"));
                assertThat(groups.values()).doesNotContainNull();
                assertThat(context.getBean(com.xa.mass.server.project.ProjectDirectory.class).require("app-checks").workerGroupIds())
                        .containsExactly("app-a-sim", "app-b-sim", "demo-sim");
                assertThat(context.getBean(com.xa.mass.server.project.ProjectTaskQueryService.class).list(CALLER, "app-checks", 100, null).tasks()).isEmpty();
            }
            assertThat(context.getBean(com.xa.mass.server.delivery.adapter.ServerWorkerDeliveryAdapterProperties.class)
                    .instances()).hasSize(preview ? 1 : 0);
            if (sms) assertThat(context.getBean(SmsReceptionService.class).isRunning()).isTrue();
            if (messages) assertThat(context.getBean(MessageTaskService.class).isRunning()).isTrue();
            var group = context.getBean(WorkerResourceCatalog.class).getWorkerGroupDescriptors(List.of("demo-sim")).get("demo-sim");
            if (sms || messages) {
                assertThat(group).isNotNull();
                if (sms) assertThat(context.getBean(SmsReceptionService.class).catalog().get("countries").toString())
                        .contains("CN", "US", "GB");
                if (messages) assertThat(context.getBean(MessageTaskService.class).catalog().get("applications").toString())
                        .contains("workerGroupId=demo-sim");
                var directory = context.getBean(com.xa.mass.server.project.ProjectDirectory.class);
                var tasks = context.getBean(com.xa.mass.server.project.ProjectTaskQueryService.class);
                assertThat(tasks.list(CALLER, "sms", 100, null).tasks()).hasSize(1);
                assertThat(tasks.list(CALLER, "messages", 100, null).tasks()).isEmpty();
                for (String project : List.of("sms", "messages")) {
                    var projectResponse = get(client, base, "/api/v1/projects/" + project);
                    assertThat(projectResponse.statusCode()).isEqualTo(200);
                    assertThat(projectResponse.body()).contains("workerGroupIds", "demo-sim").doesNotContain("managedTaskIds");
                    var listResponse = get(client, base, "/api/v1/projects/" + project + "/tasks?limit=1");
                    assertThat(listResponse.statusCode()).isEqualTo(200);
                    assertThat(listResponse.body()).contains("\"projectId\":\"" + project + "\"", "\"truncated\":false");
                    if (project.equals("sms")) assertThat(listResponse.body()).contains("PARK_WHEN_IDLE");
                    else assertThat(listResponse.body()).contains("\"tasks\":[]");
                }
            } else assertThat(group).isNull();
            assertThat(get(client, base, "/api/v1/sms/catalog").statusCode()).isEqualTo(sms ? 200 : 404);
            assertThat(get(client, base, "/api/v1/messages/catalog").statusCode()).isEqualTo(messages ? 200 : 404);
            assertThat(get(client, base, "/api/v1/app-checks/catalog").statusCode()).isEqualTo(preview ? 200 : 404);
            String openapi = get(client, base, "/v3/api-docs").body();
            assertThat(openapi.contains("/api/v1/sms/catalog")).isEqualTo(preview);
            assertThat(openapi.contains("/api/v1/messages/catalog")).isEqualTo(preview);
            assertThat(openapi.contains("/api/v1/app-checks/catalog")).isEqualTo(preview);
            String index = get(client, base, "/").body();
            assertThat(index).contains("/static/js/");
            DocumentationDelivery.verify(client, base);
            for (String page : List.of("/sms", "/sms/", "/sms/metrics", "/messages", "/messages/",
                    "/messages/tasks/example", "/messages/tasks/example/", "/app-checks", "/app-checks/",
                    "/app-checks/tasks/example", "/app-checks/tasks/example/")) {
                var response = get(client, base, page);
                assertThat(response.statusCode()).as(page).isEqualTo(200);
                assertThat(response.body()).isEqualTo(index);
            }
            for (String unknown : List.of("/messages/unknown", "/messages/assets/missing.js", "/messages/campaigns/id/extra",
                    "/api/v1/messages/unknown", "/api/v1/sms/unknown", "/static/missing.js",
                    "/app-checks/unknown", "/app-checks/tasks/example/extra", "/app-checks/assets/missing.js", "/api/v1/app-checks/unknown"))
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
        return client.send(TaskObservations.request(base, path, CALLER).timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
