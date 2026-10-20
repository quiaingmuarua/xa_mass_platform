package com.xa.mass.serverboot;

import com.xa.mass.scenario.appchecks.AppCheckTaskService;
import com.xa.mass.scenario.messages.MessageTaskService;
import com.xa.mass.scenario.sms.SmsReceptionService;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workermatching.QualifiedCountryDefinition;
import io.lettuce.core.*;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import static org.assertj.core.api.Assertions.*;

@Tag("scenario-composition")
class MessagesConsumerIntegrationTest {
    @TempDir Path directory;
    final String scope = "test_consumer_messages_" + UUID.randomUUID().toString().replace("-", "");
    final String redis = System.getenv().getOrDefault("XA_MASS_REDIS_URL", "redis://127.0.0.1:6379/15");
    static final String GROUP = "external-message-workers", EVENT = "extension.worker.message.send";

    @Test void disabledConsumerDoesNotContributeBusinessOrMatchingDefinitions() throws Exception {
        try (var context = start(false, "valid")) {
            assertThat(context.getBeansOfType(MessageTaskService.class)).isEmpty();
            assertThat(context.getBeansOfType(QualifiedCountryDefinition.class)).isEmpty();
            assertThat(context.getBean(ProjectDirectory.class).projects()).isEmpty();
        }
    }

    @Test void externalFilesEnableOnlyMessagesWithAnArbitraryApplicationAndGroup() throws Exception {
        try (var context = start(true, "valid")) {
            assertThat(context.getBeansOfType(MessageTaskService.class)).hasSize(1);
            assertThat(context.getBeansOfType(AppCheckTaskService.class)).isEmpty();
            assertThat(context.getBeansOfType(SmsReceptionService.class)).isEmpty();
            assertThat(context.getBeansOfType(QualifiedCountryDefinition.class)).hasSize(1);
            assertThat(context.getBean(ProjectDirectory.class).projects()).containsOnlyKeys("messages");
            assertThat(context.getBean(ProjectDirectory.class).require("messages").workerGroupIds()).containsExactly(GROUP);
            assertThat(context.getBean(MessageTaskService.class).catalog().get("applications").toString())
                    .contains("external-application", GROUP);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"application", "group", "event", "pool", "function"})
    void missingDependenciesDoNotOpenMessages(String missing) {
        assertThatThrownBy(() -> { try (var ignored = start(true, missing)) { } })
                .isInstanceOf(RuntimeException.class);
    }

    private ConfigurableApplicationContext start(boolean enabled, String missing) throws Exception {
        int port; try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var host = new LinkedHashMap<String, Object>();
        host.put("logging.level.root", "ERROR");
        host.put("xa.mass.redis.url", redis);
        host.put("xa.mass.redis.scope", scope);
        host.put("xa.mass.kernel-pacer.enabled", false);
        host.put("xa.mass.task-retention.enabled", false);
        host.put("xa.mass.worker-delivery.adapter.instances.runtime-websocket.listen-port", port);
        host.put("xa.mass.worker-endpoints.endpoints.runtime-websocket.public-uri",
                "ws://127.0.0.1:" + port + "/api/v1/worker-delivery/websocket");
        host.put("xa.mass.scenarios.messages.enabled", enabled);
        if (enabled && !missing.equals("application")) {
            host.put("xa.mass.scenarios.messages.applications[0].id", "external-application");
            host.put("xa.mass.scenarios.messages.applications[0].label", "External messages");
            host.put("xa.mass.scenarios.messages.applications[0].worker-group-id", GROUP);
        }
        if (enabled) {
            if (!missing.equals("pool")) host.put("xa.mass.worker-matching.groups." + GROUP + ".pools[0]", "messaging");
            if (!missing.equals("function")) host.put("xa.mass.worker-matching.groups." + GROUP + ".functions[0]", "worker.messaging.available");
        }
        Path yaml = directory.resolve("Messages 配置.yaml"), groups = directory.resolve("Group 注册.json");
        Files.writeString(yaml, Jsons.toJson(host));
        Files.writeString(groups, Jsons.toJson(Map.of(missing.equals("group") ? "another-group" : GROUP,
                Map.of("attributes", Map.of(), "eventCodes", List.of(missing.equals("event") ? "extension.worker.other" : EVENT)))));
        var app = new SpringApplication(ServerBootConfiguration.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setRegisterShutdownHook(false);
        return app.run("--spring.profiles.active=consumer", "--spring.config.additional-location=" + yaml.toUri(),
                "--xa.mass.worker-assembly.group-config-json=" + Files.readString(groups));
    }

    @AfterEach void cleanup() {
        var client = RedisClient.create(redis);
        try (var connection = client.connect()) {
            var cursor = ScanCursor.INITIAL;
            do {
                var page = connection.sync().scan(cursor, ScanArgs.Builder.matches("xa_mass:" + scope + ":*").limit(1000));
                if (!page.getKeys().isEmpty()) connection.sync().unlink(page.getKeys().toArray(String[]::new));
                cursor = page;
            } while (!cursor.isFinished());
        } finally { client.shutdown(); }
    }
}
