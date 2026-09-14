package com.xa.mass.serverboot;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.server.assembly.pacer.KernelPacerProperties;
import com.xa.mass.server.assembly.redis.XaMassRedisProperties;
import com.xa.mass.server.assembly.runtime.ServerWorkerAssemblyProperties;
import com.xa.mass.server.delivery.adapter.ServerWorkerDeliveryAdapterProperties;
import com.xa.mass.server.delivery.directcall.DirectCallProperties;
import com.xa.mass.server.task.TaskItemOutcomeProperties;
import com.xa.mass.server.task.call.TaskRpcProperties;
import com.xa.mass.server.worker.endpoint.WorkerEndpointDirectory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class DeploymentConfigurationTest {
    @TempDir Path temporary;

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({KernelPacerProperties.class, XaMassRedisProperties.class,
            ServerWorkerAssemblyProperties.class, ServerWorkerDeliveryAdapterProperties.class,
            WorkerEndpointDirectory.class, TaskRpcProperties.class, DirectCallProperties.class,
            TaskItemOutcomeProperties.class})
    static class BoundConfiguration {}

    private ApplicationContextRunner configuration(String profile, Map<String, Object> environment) {
        var runner = new ApplicationContextRunner()
                .withUserConfiguration(BoundConfiguration.class)
                .withInitializer(context -> {
                    var sources = context.getEnvironment().getPropertySources();
                    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    sources.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            new SystemEnvironmentPropertySource(
                                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment));
                })
                .withInitializer(new ConfigDataApplicationContextInitializer());
        return profile.equals("default") ? runner : runner.withPropertyValues("spring.profiles.active=" + profile);
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "scenario-workers", "agentforge", "preview"})
    void classpathProfilesBindCompleteExistingDeployments(String profile) {
        configuration(profile, profile.equals("preview") ? Map.of("XA_MASS_REDIS_SCOPE", "test_preview_config") : Map.of())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var environment = context.getEnvironment();
                    var adapters = context.getBean(ServerWorkerDeliveryAdapterProperties.class).instances();
                    var groups = context.getBean(ServerWorkerAssemblyProperties.class).groupConfigJson();
                    assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(switch (profile) {
                        case "agentforge" -> 18182;
                        case "preview" -> 18500;
                        default -> 18082;
                    });
                    assertThat(context.getBean(KernelPacerProperties.class).preset().name())
                            .isEqualTo(profile.equals("scenario-workers") ? "SCENARIO_LAB" : "DEFAULT");
                    assertThat(context.getBean(KernelPacerProperties.class).enabled()).isTrue();
                    assertThat(context.getBean(XaMassRedisProperties.class).scope()).isEqualTo(switch (profile) {
                        case "scenario-workers" -> "profile_scenario_workers";
                        case "agentforge" -> "profile_agentforge";
                        case "preview" -> "test_preview_config";
                        default -> "profile_default";
                    });
                    assertThat(environment.getProperty("spring.jackson.deserialization.fail-on-unknown-properties", Boolean.class)).isTrue();
                    assertThat(environment.getProperty("springdoc.paths-to-match")).isEqualTo("/api/v1/**");
                    assertThat(environment.getProperty("scalar.path")).isEqualTo("/scalar");
                    assertThat(context.getBean(TaskRpcProperties.class).maxWaitTimeoutMillis()).isEqualTo(60000);
                    assertThat(context.getBean(DirectCallProperties.class).maxPendingCalls()).isEqualTo(10000);
                    assertThat(context.getBean(WorkerEndpointDirectory.class).endpoints()).containsKey("system-polling");
                    if (profile.equals("default")) assertThat(adapters).isEmpty();
                    else assertThat(adapters).containsOnlyKeys(switch (profile) {
                        case "scenario-workers" -> "scenario-websocket";
                        case "agentforge" -> "agentforge-websocket";
                        default -> "products-websocket";
                    });
                    if (profile.equals("scenario-workers")) {
                        assertThat(groups).contains("scenario-phone-number-workers", "scenario-string-utils-workers", "android-demo-workers");
                        assertThat(context.getBean(TaskItemOutcomeProperties.class).names())
                                .containsEntry(7, "delivered").containsEntry(8, "read").containsEntry(9, "replied");
                    } else assertThat(groups).isEqualTo("{}");
                });
    }

    @Test
    void previewRequiresItsScope() {
        configuration("preview", Map.of()).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("Redis scope must match");
        });
    }

    @Test
    void environmentAndExplicitPropertiesRetainTheirOverrides() {
        configuration("agentforge", Map.of("XA_MASS_AGENTFORGE_SERVER_PORT", "19182",
                "XA_MASS_AGENTFORGE_ADAPTER_PORT", "19183", "XA_MASS_REDIS_SCOPE", "test_environment",
                "XA_MASS_REDIS_URL", "redis://127.0.0.1:6391/15"))
                .withPropertyValues("server.port=19282")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("19282");
                    assertThat(context.getBean(XaMassRedisProperties.class).scope()).isEqualTo("test_environment");
                    assertThat(context.getBean(XaMassRedisProperties.class).url().getPort()).isEqualTo(6391);
                    assertThat(context.getBean(ServerWorkerDeliveryAdapterProperties.class).instances()
                            .get("agentforge-websocket").listenPort()).isEqualTo(19183);
                    assertThat(context.getBean(WorkerEndpointDirectory.class).find("agentforge-websocket")
                            .publicUri().getPort()).isEqualTo(19183);
                });
    }

    @Test
    void externalProfileOverridesOnlySuppliedFields() throws Exception {
        Files.writeString(temporary.resolve("application-agentforge.yaml"), """
                server:
                  port: 19382
                xa:
                  mass:
                    worker-delivery:
                      adapter:
                        instances:
                          agentforge-websocket:
                            report-queue-capacity: 2345
                """);
        configuration("agentforge", Map.of())
                .withPropertyValues("spring.config.additional-location=" + temporary.toUri())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("19382");
                    var adapter = context.getBean(ServerWorkerDeliveryAdapterProperties.class).instances().get("agentforge-websocket");
                    assertThat(adapter.reportQueueCapacity()).isEqualTo(2345);
                    assertThat(adapter.listenPort()).isEqualTo(18183);
                    assertThat(adapter.commandRetryCapacity()).isEqualTo(1000);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"xa.mass.kernel-pacer.preset=INVALID",
            "xa.mass.worker-delivery.adapter.instances.agentforge-websocket.report-queue-capacity=0"})
    void invalidDeploymentInputStillFailsBinding(String override) {
        configuration("agentforge", Map.of()).withPropertyValues(override)
                .run(context -> assertThat(context).hasFailed());
    }
}
