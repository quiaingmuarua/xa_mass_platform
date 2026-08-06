package com.xa.mass.worker.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class AndroidWorkerArchitectureBoundaryTest {

    @Test
    public void moduleOwnsAndroidAssemblyWithoutJavaOrServerDependencies()
            throws IOException {
        Path project = Path.of("").toAbsolutePath();
        String source = readTree(project.resolve("src/main/java"));
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
        assertTrue(networkClient.contains("private enum State"));
        assertTrue(networkClient.contains("RECONNECT_SCHEDULED"));
        assertTrue(networkClient.contains("HandlerThread"));
        assertTrue(source.contains("public final class AndroidWorker"));
        assertTrue(source.contains("new TextMessageWorkerRuntime("));
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
