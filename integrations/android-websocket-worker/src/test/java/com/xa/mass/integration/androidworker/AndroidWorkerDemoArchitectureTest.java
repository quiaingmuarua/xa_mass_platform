package com.xa.mass.integration.androidworker;

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
public class AndroidWorkerDemoArchitectureTest {

    @Test
    public void applicationComposesOnlyPublicWorkerLayers()
            throws IOException {
        Path project = Path.of("").toAbsolutePath();
        String build = read(project.resolve("build.gradle"));
        String source = readTree(project.resolve("src/main/java"));
        String mainManifest = read(project.resolve(
                "src/main/AndroidManifest.xml"
        ));
        String debugManifest = read(project.resolve(
                "src/debug/AndroidManifest.xml"
        ));

        assertTrue(build.contains("id 'com.android.application'"));
        assertTrue(build.contains(
                "project(':transport:worker-core')"
        ));
        assertTrue(build.contains(
                "project(':transport:android-client')"
        ));
        assertTrue(build.contains(
                "project(':transport:okhttp-worker')"
        ));
        assertTrue(mainManifest.contains("android.permission.INTERNET"));
        assertFalse(mainManifest.contains("usesCleartextTraffic"));
        assertTrue(debugManifest.contains(
                "android:usesCleartextTraffic=\"true\""
        ));

        for (String forbidden : new String[]{
                "server_jvm",
                "kernel_jvm",
                "scenario_workers_jvm",
                "transport:netty-adapter",
                "org.springframework",
                "io.netty",
                "redis",
                "items:call",
                "WorkManager",
                "android.app.Service"
        }) {
            assertFalse(forbidden, build.contains(forbidden));
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
