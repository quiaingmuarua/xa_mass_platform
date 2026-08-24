package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ScenarioWorkersArchitectureBoundaryTest {

    @Test
    void publicSurfaceExposesOnlyAggregateLifecycle() {
        assertThat(Modifier.isFinal(
                ScenarioWorkers.class.getModifiers()
        )).isTrue();
        assertThat(java.util.Arrays.stream(
                ScenarioWorkers.class.getDeclaredMethods()
        ).filter(method -> Modifier.isPublic(method.getModifiers())))
                .extracting(Method::getName)
                .containsExactlyInAnyOrder(
                        "fromJson",
                        "start",
                        "close"
                );
        assertThat(ScenarioWorkers.class.getConstructors())
                .isEmpty();
        assertThat(Modifier.isPublic(
                ScenarioWorkerGroupConfig.class.getModifiers()
        )).isFalse();
        assertThat(Modifier.isPublic(
                PhoneNumberWorkerEvents.class.getModifiers()
        )).isFalse();
        assertThat(Modifier.isPublic(
                StringUtilityWorkerEvents.class.getModifiers()
        )).isFalse();
        Method fromJson = java.util.Arrays.stream(
                ScenarioWorkers.class.getDeclaredMethods()
        ).filter(method -> method.getName().equals("fromJson"))
                .findFirst()
                .orElseThrow();
        assertThat(fromJson.getParameterTypes())
                .containsExactly(
                        String.class,
                        String.class,
                        java.net.URI.class
                );
    }

    @Test
    void moduleOwnsOnlyTheLabAndWorkerTransportAssembly()
            throws Exception {
        String sources = readSources(Path.of("src/main/java"));
        String build = Files.readString(Path.of("build.gradle"));

        assertThat(sources)
                .contains("availableExtensionsByEventCode")
                .contains("JavaWorkerManager.builder")
                .contains(".extendEventDefinitions(")
                .contains(".replica(")
                .contains("ScenarioWorkerLab")
                .contains("ScenarioWorkerStateFile")
                .contains("ScenarioWorkersJsonParser");
        assertThat(sources)
                .doesNotContain("HostResources")
                .doesNotContain("hostResources")
                .doesNotContain("WorkerExecutionResources")
                .doesNotContain("Executors.new")
                .doesNotContain("interface WorkerHost")
                .doesNotContain("interface WorkerReference")
                .doesNotContain("FileLock")
                .doesNotContain("worker.lock")
                .doesNotContain("WorkerIdentityStore")
                .doesNotContain("java.net.http.HttpClient")
                .doesNotContain("new OkHttpWorkerControlClient")
                .doesNotContain("new OkHttpTextWebSocketClient");
        assertThat(sources)
                .doesNotContain("com.xa.mass.kernel")
                .doesNotContain("org.springframework")
                .doesNotContain("com.xa.mass.server")
                .doesNotContain("io.lettuce")
                .doesNotContain("ScoreBand")
                .doesNotContain("Pacer")
                .doesNotContain("@RestController")
                .doesNotContain("Class.forName")
                .doesNotContain("java.lang.reflect")
                .doesNotContain("ServiceLoader");
        assertThat(build)
                .contains("id 'application'")
                .contains(
                        "com.xa.mass.scenarioworkers."
                                + "ScenarioWorkerHostMain"
                )
                .contains(
                        "implementation "
                                + "project(':transport:worker-core')"
                )
                .contains(
                        "implementation "
                                + "project("
                                + "':transport:worker-delivery-contract')"
                )
                .contains(
                        "implementation "
                                + "project(':transport:java-worker')"
                )
                .doesNotContain("kernel_jvm")
                .doesNotContain("api project")
                .doesNotContain("transport:netty-adapter")
                .doesNotContain("server_jvm")
                .doesNotContain("spring");
    }

    @Test
    void transportModulesDoNotDependOnScenarioWorkers()
            throws Exception {
        String transportBuilds = readFiles(
                Path.of("../transport"),
                "build.gradle"
        );
        assertThat(transportBuilds)
                .doesNotContain("scenario_workers_jvm");
    }

    private static String readSources(Path root) throws Exception {
        return readFiles(root, ".java");
    }

    private static String readFiles(
            Path root,
            String suffix
    ) throws Exception {
        StringBuilder sources = new StringBuilder();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> path.toString().endsWith(suffix))
                    .sorted()
                    .forEach(path -> {
                        try {
                            sources.append(Files.readString(path));
                        } catch (Exception error) {
                            throw new IllegalStateException(error);
                        }
                    });
        }
        return sources.toString();
    }
}
