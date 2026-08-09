package com.xa.mass.transport.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.transport.client.jdk.JdkLineSocketClient;
import com.xa.mass.transport.client.okhttp.OkHttpTextWebSocketClient;
import com.xa.mass.transport.client.okhttp.OkHttpWorkerControlClient;
import com.xa.mass.transport.client.okhttp.OkHttpWorkerPointClient;
import com.xa.mass.worker.javase.JavaWorkerManager;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

class ConcreteWorkerClientArchitectureTest {

    @Test
    void moduleContainsJavaAssemblyAndJavaElevenNetworkImplementations()
            throws IOException {
        Path project = Path.of("").toAbsolutePath();
        String source = readTree(project.resolve("src/main/java"));
        String assembly = Files.readString(project.resolve(
                "src/main/java/com/xa/mass/worker/javase/JavaWorker.java"
        ));
        String build = Files.readString(project.resolve("build.gradle"));

        assertTrue(build.contains("id 'java-library'"));
        assertTrue(build.contains(
                "archivesName.set('xa-mass-java-worker')"
        ));
        assertTrue(build.contains(
                "api project(':transport:worker-core')"
        ));
        assertTrue(build.contains(
                "implementation 'com.squareup.okhttp3:okhttp:5.3.0'"
        ));
        assertTrue(build.contains("options.release = 11"));
        assertFalse(build.contains("worker_delivery_contract_jvm"));

        for (String forbidden : new String[]{
                "class PollingWorkerTransport",
                "WorkerResult",
                "WorkerConnectionHello",
                "WorkerCommandExecutor",
                "android.",
                "androidx.",
                "server_jvm",
                "kernel_jvm",
                "springframework",
                "io.lettuce",
                "TaskType"
        }) {
            assertFalse(source.contains(forbidden), forbidden);
        }
        assertTrue(source.contains("public final class JavaWorker"));
        assertTrue(source.contains("implements WorkerLifecycle"));
        assertTrue(source.contains("new RegisteredWorkerPreparation("));
        assertTrue(source.contains("new WorkerLoop("));
        assertTrue(assembly.contains("WorkerExecutionResources"));
        assertTrue(assembly.contains(
                "public Builder executionResources("
        ));
        assertFalse(assembly.contains("isConnected("));
        assertFalse(assembly.contains("Executors.new"));
        assertFalse(assembly.contains("shutdownNow("));
        assertTrue(source.contains("WorkerTransportType.WEBSOCKET"));
        assertTrue(source.contains("new JdkLineSocketClient("));
    }

    @Test
    void publicNetworkClientApiDoesNotExposeLibraries() {
        for (Class<?> type : new Class<?>[]{
                OkHttpWorkerPointClient.class,
                OkHttpTextWebSocketClient.class,
                OkHttpWorkerControlClient.class,
                JdkLineSocketClient.class
        }) {
            for (Constructor<?> constructor : type.getConstructors()) {
                assertNarrow(constructor.toGenericString());
            }
            for (Method method : type.getMethods()) {
                if (method.getDeclaringClass() != Object.class) {
                    assertNarrow(method.toGenericString());
                }
            }
        }
    }

    @Test
    void clientsLiveUnderConcreteNetworkPackages()
            throws IOException {
        Path project = Path.of("").toAbsolutePath();
        String clients = readFiles(
                project,
                "src/main/java/com/xa/mass/transport/client/okhttp/"
                        + "OkHttpWorkerPointClient.java",
                "src/main/java/com/xa/mass/transport/client/okhttp/"
                        + "OkHttpTextWebSocketClient.java",
                "src/main/java/com/xa/mass/transport/client/jdk/"
                        + "JdkLineSocketClient.java"
        );

        assertTrue(clients.contains(
                "package com.xa.mass.transport.client.okhttp;"
        ));
        assertTrue(clients.contains(
                "package com.xa.mass.transport.client.jdk;"
        ));
    }

    @Test
    void javaManagerOwnsOnlyOneFixedGroupAndDesiredState()
            throws IOException {
        Path project = Path.of("").toAbsolutePath();
        String manager = Files.readString(project.resolve(
                "src/main/java/com/xa/mass/worker/javase/"
                        + "JavaWorkerManager.java"
        ));
        String resources = Files.readString(project.resolve(
                "src/main/java/com/xa/mass/worker/javase/"
                        + "JavaWorkerHostResources.java"
        ));

        assertTrue(AutoCloseable.class.isAssignableFrom(
                JavaWorkerManager.class
        ));
        assertFalse(com.xa.mass.worker.runtime.WorkerLifecycle.class
                .isAssignableFrom(JavaWorkerManager.class));
        assertFalse(manager.contains("Executors.new"));
        assertFalse(manager.contains("new Thread"));
        assertFalse(manager.contains("shutdown"));
        assertTrue(resources.contains("Executors.newFixedThreadPool"));
        assertTrue(resources.contains(
                "Executors.newSingleThreadScheduledExecutor"
        ));
        assertTrue(manager.contains("JavaWorker.Builder"));
        assertTrue(manager.contains("desiredRunning"));
        assertFalse(manager.contains("class WorkerKey"));
        assertFalse(manager.contains("ManagedWorkerSnapshot"));
        assertFalse(manager.contains(" register("));
        assertFalse(manager.contains(" scale"));
        assertFalse(manager.contains("addListener("));
        assertFalse(manager.contains("onSnapshot("));
        assertFalse(manager.contains("isConnected("));
        assertFalse(manager.contains(".schedule("));
        assertFalse(manager.contains("generation"));
        assertFalse(manager.contains("runId"));
    }

    private static void assertNarrow(String signature) {
        for (String forbidden : new String[]{
                "okhttp3",
                "java.net.Socket",
                "WorkerCommand",
                "WorkerResult"
        }) {
            assertFalse(signature.contains(forbidden), signature);
        }
    }

    private static String readTree(Path root) throws IOException {
        StringBuilder source = new StringBuilder();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .forEach(path -> append(source, path));
        }
        return source.toString();
    }

    private static String readFiles(
            Path project,
            String... relativePaths
    ) throws IOException {
        StringBuilder source = new StringBuilder();
        for (String relativePath : relativePaths) {
            source.append(
                    Files.readString(project.resolve(relativePath))
            );
        }
        return source.toString();
    }

    private static void append(StringBuilder target, Path path) {
        try {
            target.append(Files.readString(path));
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Unable to read " + path,
                    error
            );
        }
    }
}
