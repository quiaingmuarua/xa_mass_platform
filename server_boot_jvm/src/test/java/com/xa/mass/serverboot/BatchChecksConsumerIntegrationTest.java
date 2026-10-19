package com.xa.mass.serverboot;

import com.xa.mass.scenario.appchecks.AppCheckTaskService;
import com.xa.mass.scenario.messages.MessageTaskService;
import com.xa.mass.scenario.sms.SmsReceptionService;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.workerdelivery.json.Jsons;
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
class BatchChecksConsumerIntegrationTest {
    @TempDir Path directory;
    final String scope = "test_consumer_checks_" + UUID.randomUUID().toString().replace("-", "");
    final String redis = System.getenv().getOrDefault("XA_MASS_REDIS_URL", "redis://127.0.0.1:6379/15");
    static final String GROUP = "external-check-workers", EVENT = "extension.worker.android.string.digest";

    @Test void consumerKeepsScenarioDisabledWithoutOpeningItsStore() throws Exception {
        try (var context = start(false, "valid")) {
            assertThat(context.getBeansOfType(AppCheckTaskService.class)).isEmpty();
            assertThat(context.getBeansOfType(SmsReceptionService.class)).isEmpty();
            assertThat(context.getBeansOfType(MessageTaskService.class)).isEmpty();
            assertThat(context.getBean(ProjectDirectory.class).projects()).containsOnlyKeys("default");
            assertThat(directory.resolve("data/configuration.json")).doesNotExist();
            assertThat(directory.resolve("data/configuration.json.lock")).doesNotExist();
        }
    }
    @Test void consumerEnablesOneScenarioWithArbitraryConfiguredGroupAndCheckNames() throws Exception {
        try (var context = start(true, "valid")) {
            assertThat(context.getBeansOfType(AppCheckTaskService.class)).hasSize(1);
            assertThat(context.getBeansOfType(SmsReceptionService.class)).isEmpty();
            assertThat(context.getBeansOfType(MessageTaskService.class)).isEmpty();
            assertThat(context.getBean(ProjectDirectory.class).projects()).containsOnlyKeys("default", "app-checks");
            assertThat(context.getBean(ProjectDirectory.class).require("app-checks").workerGroupIds()).containsExactly(GROUP);
            assertThat((List<?>) context.getBean(AppCheckTaskService.class).catalog().get("checks")).hasSize(1);
        }
        assertThat(directory.resolve("data/configuration.json")).exists();
        // A restart opens the saved document without copying the seed again.
        String before = Files.readString(directory.resolve("data/configuration.json"));
        try (var ignored = start(true, "valid")) {
            assertThat(Files.readString(directory.resolve("data/configuration.json"))).isEqualTo(before);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"group", "event", "pool", "function", "contract"})
    void missingCapabilitiesNeverOpenTheConsumer(String missing) {
        assertThatThrownBy(() -> { try (var ignored = start(true, missing)) { } }).isInstanceOf(RuntimeException.class);
    }
    @Test void moduleProjectAndExplicitHostBindingsRemainIndependent() throws Exception {
        try (var context = start(true, "window-pool")) {
            var bindings = context.getBean(com.xa.mass.server.worker.observation.WorkerGroupBindings.class);
            assertThat(bindings.group(GROUP).fixedWindowPools()).singleElement().satisfies(pool ->
                    assertThat(pool.poolName()).isEqualTo("module-window"));
            assertThat(bindings.group(GROUP).fixedWindowPools().stream().map(pool -> pool.feedback()).toList()).singleElement().satisfies(feedback ->
                    assertThat(feedback.messageEvents()).containsExactly(EVENT));
            assertThat(context.getBean(ProjectDirectory.class).require("app-checks").workerGroupIds()).containsExactly(GROUP);
        }
    }
    @SuppressWarnings("unchecked")
    private ConfigurableApplicationContext start(boolean enabled, String missing) throws Exception {
        var root = Path.of(System.getProperty("xa.mass.repository.root"));
        var seed = Jsons.parseObject(Files.readString(root.resolve("xa-android/config/batch-checks-configuration.json")));
        var check = (Map<String,Object>) ((Map<?,?>) seed.get("checks")).get("android-md5-prefix");
        check.put("workerGroupId", GROUP);
        if (missing.equals("contract")) check.put("checkType", "missing-protocol");
        seed.put("checks", Map.of("external-check", check));
        Path seedFile = directory.resolve("seed.json");
        Files.writeString(seedFile, Jsons.toJson(seed));
        int port; try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var args = new ArrayList<>(List.of("--spring.profiles.active=consumer", "--logging.level.root=ERROR",
                "--xa.mass.redis.url=" + redis, "--xa.mass.redis.scope=" + scope,
                "--xa.mass.kernel-pacer.enabled=false", "--xa.mass.task-retention.enabled=false",
                "--xa.mass.worker-delivery.adapter.instances.runtime-websocket.listen-port=" + port,
                "--xa.mass.worker-endpoints.endpoints.runtime-websocket.public-uri=ws://127.0.0.1:" + port + "/api/v1/worker-delivery/websocket",
                "--xa.mass.project-assembly.projects[0].project-id=default",
                "--xa.mass.scenarios.app-checks.enabled=" + enabled,
                "--xa.mass.scenarios.app-checks.configuration.path=" + directory.resolve("data/configuration.json"),
                "--xa.mass.scenarios.app-checks.configuration.seed=" + seedFile.toUri(),
                "--xa.mass.worker-assembly.group-config-json=" + Jsons.toJson(Map.of(
                    missing.equals("group") ? "another-group" : GROUP,
                    Map.of("attributes", Map.of(), "eventCodes", List.of(missing.equals("event") ? "extension.worker.other" : EVENT))))));
        if (!missing.equals("pool")) args.add("--xa.mass.worker-matching.groups." + GROUP + ".pools[0]=any");
        if (!missing.equals("function")) args.add("--xa.mass.worker-matching.groups." + GROUP + ".functions[0]=worker.any");
        if (missing.equals("window-pool")) {
            String prefix = "--xa.mass.worker-assembly.fixed-window.";
            args.addAll(List.of(prefix + "pools.module-window.function-name=worker.module.available",
                    prefix + "pools.module-window.timestamp-property=moduleAt", prefix + "pools.module-window.count-property=moduleCount",
                    prefix + "pools.module-window.window-millis=60000", prefix + "pools.module-window.max-count=10",
                    prefix + "pools.module-window.feedback.message-events[0]=" + EVENT,
                    prefix + "groups." + GROUP + ".pools.module-window.window-millis=60000"));
        }
        // Exercise the same three external inputs as source/ZIP launchers.
        var host = new LinkedHashMap<String,Object>();
        var command = new ArrayList<String>();
        for (String arg : args) {
            int equals = arg.indexOf('=');
            String name = arg.substring(2, equals), value = arg.substring(equals + 1);
            if (name.equals("spring.profiles.active")) command.add(arg);
            else if (name.equals("xa.mass.worker-assembly.group-config-json")) {
                Path groupsFile = directory.resolve("Group 注册.json");
                Files.writeString(groupsFile, value);
                command.add("--" + name + "=" + Files.readString(groupsFile));
            } else host.put(name, value);
        }
        Path runtimeFile = directory.resolve("Runtime 配置.yaml");
        Files.writeString(runtimeFile, Jsons.toJson(host));
        command.add("--spring.config.additional-location=" + runtimeFile.toUri());
        var app = new SpringApplication(ServerBootConfiguration.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setRegisterShutdownHook(false);
        return app.run(command.toArray(String[]::new));
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
