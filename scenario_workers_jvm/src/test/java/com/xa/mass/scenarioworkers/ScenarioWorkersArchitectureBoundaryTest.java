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
    void publicSurfaceIsFiniteAndDoesNotExposeImplementations() {
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
                ScenarioWorkerBundleConfig.class.getModifiers()
        )).isFalse();
        assertThat(Modifier.isPublic(
                PhoneNumberWorkerBundle.class.getModifiers()
        )).isFalse();
        assertThat(Modifier.isPublic(
                StringUtilityWorkerBundle.class.getModifiers()
        )).isFalse();
        assertThat(Modifier.isPublic(
                PhoneNumberCapability.class.getModifiers()
        )).isFalse();
        assertThat(Modifier.isPublic(
                StringUtilityCapability.class.getModifiers()
        )).isFalse();
    }

    @Test
    void moduleUsesOwnerContractsAndWorkerTransportOnly()
            throws Exception {
        String sources = readSources(Path.of("src/main/java"));
        String build = Files.readString(Path.of("build.gradle"));

        assertThat(sources)
                .contains("WorkerResourceCatalog")
                .contains("WorkerRuntime")
                .contains("WebSocketWorkerTransport")
                .contains("OkHttpTextWebSocketClient")
                .contains("ScenarioWorkersJsonParser");
        assertThat(sources)
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
                .contains("api project(':kernel_jvm')")
                .contains(
                        "implementation "
                                + "project(':worker_delivery_contract_jvm')"
                )
                .contains(
                        "implementation "
                                + "project(':transport:okhttp-worker')"
                )
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
