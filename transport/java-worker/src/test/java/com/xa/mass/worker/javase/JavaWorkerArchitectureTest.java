package com.xa.mass.worker.javase;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.transport.client.okhttp.OkHttpWorkerPointClient;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

class JavaWorkerArchitectureTest {

    @Test
    void moduleContainsJavaAssemblyAndJavaElevenNetworkImplementations()
            throws IOException {
        Path project = Path.of("").toAbsolutePath();
        String source = readTree(project.resolve("src/main/java"));
        String assembly = Files.readString(project.resolve(
                "src/main/java/com/xa/mass/worker/javase/JavaWorker.java"
        ));
        String webSocketClient = Files.readString(project.resolve(
                "src/main/java/com/xa/mass/worker/javase/"
                        + "JavaOkHttpTextWebSocketClient.java"
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
                "DeliveryReport",
                "WorkerConnectionHello",
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
        assertTrue(source.contains("new WorkerRunController("));
        assertFalse(source.contains("WorkerLoop"));
        assertFalse(source.contains("WorkerRetryPolicy"));
        assertFalse(source.contains("WorkerExecutionResources"));
        assertFalse(source.contains("UUID.fromString("));
        assertFalse(assembly.contains("hostResources"));
        assertFalse(assembly.contains("controlExecutor"));
        assertFalse(assembly.contains("isConnected("));
        assertFalse(assembly.contains("Executors.new"));
        assertFalse(assembly.contains("shutdownNow("));
        assertTrue(source.contains("WorkerTransportType.WEBSOCKET"));
        assertTrue(source.contains("new JavaLineSocketClient("));
        assertFalse(webSocketClient.contains(
                "TextMessageReconnectState"
        ));
        assertFalse(webSocketClient.contains("generation"));
        assertFalse(webSocketClient.contains(
                "execute(() -> message"
        ));
        assertTrue(webSocketClient.contains("message(attempt, text);"));
    }

    @Test
    void publicNetworkClientApiDoesNotExposeLibraries() {
        for (Class<?> type : new Class<?>[]{OkHttpWorkerPointClient.class}) {
            for (Constructor<?> constructor : type.getConstructors()) {
                assertNarrow(constructor.toGenericString());
            }
            for (Method method : type.getMethods()) {
                if (method.getDeclaringClass() != Object.class) {
                    assertNarrow(method.toGenericString());
                }
            }
        }
        assertFalse(Modifier.isPublic(
                JavaWorkerPlatform.class.getModifiers()
        ));
        assertFalse(Stream.of(JavaWorker.class.getDeclaredClasses())
                .anyMatch(type -> type.getSimpleName().equals("Builder")));
        for (Method method : JavaWorker.class.getMethods()) {
            assertFalse(method.getName().equals("builder"));
        }
        assertFalse(Modifier.isPublic(
                JavaOkHttpTextWebSocketClient.class.getModifiers()
        ));
        assertFalse(Modifier.isPublic(
                JavaOkHttpWorkerControlClient.class.getModifiers()
        ));
        assertFalse(Modifier.isPublic(
                JavaLineSocketClient.class.getModifiers()
        ));
    }

    @Test
    void longConnectionClientsAreInternalToJavaAssembly()
            throws IOException {
        Path project = Path.of("").toAbsolutePath();
        String clients = readFiles(
                project,
                "src/main/java/com/xa/mass/transport/client/okhttp/"
                        + "OkHttpWorkerPointClient.java",
                "src/main/java/com/xa/mass/worker/javase/"
                        + "JavaOkHttpTextWebSocketClient.java",
                "src/main/java/com/xa/mass/worker/javase/"
                        + "JavaLineSocketClient.java"
        );

        assertTrue(clients.contains(
                "package com.xa.mass.transport.client.okhttp;"
        ));
        assertTrue(clients.contains(
                "package com.xa.mass.worker.javase;"
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
                        + "JavaWorkerPlatform.java"
        ));
        assertFalse(Files.exists(project.resolve(
                "src/main/java/com/xa/mass/worker/javase/"
                        + "JavaWorker" + "HostResources.java"
        )));

        assertTrue(AutoCloseable.class.isAssignableFrom(
                JavaWorkerManager.class
        ));
        assertFalse(com.xa.mass.worker.runtime.WorkerLifecycle.class
                .isAssignableFrom(JavaWorkerManager.class));
        assertFalse(manager.contains("Executors.new"));
        assertFalse(manager.contains("new Thread"));
        assertFalse(manager.contains("shutdown"));
        assertTrue(resources.contains("Executors.newFixedThreadPool"));
        assertTrue(resources.contains("ScheduledExecutorService"));
        assertFalse(resources.contains("commandExecutor"));
        assertFalse(resources.contains("maxConcurrentCommands"));
        assertFalse(resources.contains("SynchronousQueue"));
        assertFalse(resources.contains("retryScheduler"));
        assertFalse(resources.contains("ConcurrentHashMap"));
        assertTrue(manager.contains("JavaWorkerAssembly.assemble"));
        assertTrue(manager.contains("extendEventDefinitions("));
        assertFalse(manager.contains("eventDefinitions("));
        assertTrue(manager.contains("WorkerConnectionOptions"));
        assertTrue(manager.contains("desiredRunning"));
        assertFalse(manager.contains("scheduledStarts"));
        assertFalse(manager.contains("submitStart"));
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
                "DeliveryCommand",
                "DeliveryReport"
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
