package com.xa.mass.workersimulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class WorkerSimulatorArchitectureBoundaryTest {

    @Test
    void publicSurfaceExposesOnlyAggregateLifecycle() {
        assertThat(Modifier.isFinal(
                WorkerSimulator.class.getModifiers()
        )).isTrue();
        assertThat(java.util.Arrays.stream(
                WorkerSimulator.class.getDeclaredMethods()
        ).filter(method -> Modifier.isPublic(method.getModifiers())))
                .extracting(Method::getName)
                .containsExactlyInAnyOrder(
                        "start",
                        "close"
                );
        assertThat(WorkerSimulator.class.getConstructors())
                .isEmpty();
        assertThat(Modifier.isPublic(
                WorkerSimulatorGroupConfig.class.getModifiers()
        )).isFalse();
        assertThat(Modifier.isPublic(
                PhoneNumberWorkerEvents.class.getModifiers()
        )).isFalse();
        assertThat(Modifier.isPublic(
                StringUtilityWorkerEvents.class.getModifiers()
        )).isFalse();

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
                .contains("WorkerSimulatorLab")
                .contains("WorkerSimulatorStateFile")
                .contains("WorkerSimulatorJsonParser");
        assertThat(sources)
                .doesNotContain("HostResources")
                .doesNotContain("hostResources")
                .doesNotContain("WorkerExecutionResources")
                .doesNotContain("interface WorkerHost")
                .doesNotContain("interface WorkerReference")
                .doesNotContain("FileLock")
                .doesNotContain("worker.lock")
                .doesNotContain("WorkerIdentityStore")
                .doesNotContain("java.net.http.HttpClient")
                .doesNotContain("new OkHttpWorkerControlClient")
                .doesNotContain("new OkHttpTextWebSocketClient");
        assertThat(sources)
                .containsOnlyOnce("new ThreadPoolExecutor(")
                .containsOnlyOnce("new ArrayBlockingQueue<>(128)")
                .containsOnlyOnce("HttpServer.create(")
                .containsOnlyOnce("public static void main(")
                .doesNotContain("Executors.newSingleThreadExecutor")
                .doesNotContain("Executors.newCachedThreadPool");
        assertThat(sources)
                .doesNotContain("com.xa.mass.kernel")
                .doesNotContain("org.springframework")
                .doesNotContain("com.xa.mass.server")
                .doesNotContain("com.xa.mass.sms.backend")
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
                        "com.xa.mass.workersimulator."
                                + "WorkerSimulatorMain"
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
        assertThat(Files.readString(Path.of(
                "src/main/java/com/xa/mass/workersimulator/"
                        + "WorkerSimulatorStateFile.java"
        ))).doesNotContain("AtomicMoveNotSupportedException");
    }

    @Test
    void transportModulesDoNotDependOnWorkerSimulator()
            throws Exception {
        String transportBuilds = readFiles(
                Path.of("../transport"),
                "build.gradle"
        );
        assertThat(transportBuilds)
                .doesNotContain("worker_simulator_jvm");
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
