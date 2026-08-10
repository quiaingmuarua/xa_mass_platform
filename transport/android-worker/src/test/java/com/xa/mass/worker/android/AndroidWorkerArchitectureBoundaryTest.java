package com.xa.mass.worker.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

@RunWith(RobolectricTestRunner.class)
public class AndroidWorkerArchitectureBoundaryTest {

    @Test
    public void moduleOwnsAndroidAssemblyWithoutJavaOrServerDependencies()
            throws IOException {
        Path project = Path.of("").toAbsolutePath();
        String source = readTree(project.resolve("src/main/java"));
        String assembly = read(project.resolve(
                "src/main/java/com/xa/mass/worker/android/AndroidWorker.java"
        ));
        String build = read(project.resolve("build.gradle"));
        String manifest = read(
                project.resolve("src/main/AndroidManifest.xml")
        );

        assertTrue(build.contains("id 'com.android.library'"));
        assertTrue(build.contains(
                "api project(':transport:worker-core')"
        ));
        assertFalse(build.contains(
                "project(':transport:java-worker')"
        ));
        assertTrue(build.contains(
                "implementation 'com.squareup.okhttp3:okhttp:5.3.0'"
        ));
        assertTrue(build.contains("minSdk = 24"));
        assertTrue(build.contains("compileSdk = 36"));
        assertTrue(manifest.contains("android.permission.INTERNET"));

        String networkClient = read(project.resolve(
                "src/main/java/com/xa/mass/worker/android/"
                        + "AndroidOkHttpTextWebSocketClient.java"
        ));
        assertFalse(networkClient.contains(
                "TextMessageReconnectState"
        ));
        assertFalse(networkClient.contains("generation"));
        assertFalse(networkClient.contains("Map<"));
        assertFalse(networkClient.contains("ConcurrentHashMap"));
        assertFalse(networkClient.contains("HandlerThread"));
        assertFalse(networkClient.contains("new OkHttpClient"));
        assertFalse(networkClient.contains("Executors.new"));
        assertFalse(networkClient.contains("postConnectionCallback"));
        assertFalse(networkClient.contains(
                "handler.post(() -> message"
        ));
        assertTrue(networkClient.contains("message(attempt, text);"));
        String resources = read(project.resolve(
                "src/main/java/com/xa/mass/worker/android/"
                        + "AndroidWorkerHostResources.java"
        ));
        assertTrue(resources.contains("new HandlerThread("));
        assertFalse(resources.contains("commandExecutor"));
        assertFalse(resources.contains("maxConcurrentCommands"));
        assertFalse(resources.contains("SynchronousQueue"));
        assertTrue(source.contains("public final class AndroidWorker"));
        assertTrue(source.contains("implements WorkerLifecycle"));
        assertTrue(source.contains("new RegisteredWorkerPreparation("));
        assertTrue(source.contains("new WorkerRunController("));
        assertFalse(source.contains("WorkerLoop"));
        assertFalse(source.contains("WorkerRetryPolicy"));
        assertFalse(source.contains("WorkerExecutionResources"));
        assertTrue(assembly.contains(
                "public Builder hostResources("
        ));
        assertFalse(assembly.contains("controlExecutor"));
        assertFalse(assembly.contains("isConnected("));
        assertFalse(assembly.contains("Executors.new"));
        assertFalse(assembly.contains("shutdownNow("));
        assertFalse(source.contains("public enum State"));
        assertFalse(source.contains("public static final class Snapshot"));
        assertTrue(source.contains("WorkerTransportType.WEBSOCKET"));
        assertTrue(source.contains("SharedPreferences"));
        assertFalse(source.contains("AndroidWorkerEndpointCacheStore"));
        assertFalse(source.contains("AndroidWorkerPropertiesFingerprint"));
        assertFalse(source.contains("pendingMessages"));
        assertFalse(source.contains("MessageInterceptor"));

        for (String forbidden : new String[]{
                "server_jvm",
                "kernel_jvm",
                "transport:java-worker",
                "com.xa.mass.transport.client.okhttp",
                "io.netty",
                "springframework",
                "redis",
                "android.app.Activity",
                "android.app.Service"
        }) {
            assertFalse(forbidden, source.contains(forbidden));
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

    private static void append(StringBuilder target, Path path) {
        try {
            target.append(read(path));
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Unable to read " + path,
                    error
            );
        }
    }

    private static String read(Path path) throws IOException {
        return new String(
                Files.readAllBytes(path),
                StandardCharsets.UTF_8
        );
    }
}
