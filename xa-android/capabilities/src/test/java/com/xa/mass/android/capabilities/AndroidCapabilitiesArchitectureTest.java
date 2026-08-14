package com.xa.mass.android.capabilities;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class AndroidCapabilitiesArchitectureTest {

    @Test
    public void capabilitiesDependOnlyOnWorkerCoreAndAndroid()
            throws IOException {
        Path project = Path.of("").toAbsolutePath();
        String build = read(project.resolve("build.gradle"));
        String source = readTree(project.resolve("src/main/java"));

        assertTrue(build.contains("id 'com.android.library'"));
        assertTrue(build.contains(
                "api project(':transport:worker-core')"
        ));
        for (String forbidden : new String[]{
                "transport:android-worker",
                "transport:java-worker",
                "transport:netty-adapter",
                "server_jvm",
                "kernel_jvm",
                "scenario_workers_jvm",
                "org.springframework",
                "io.netty",
                "redis",
                "AgentForge",
                "ServiceLoader",
                "Class.forName",
                "AndroidCapability",
                "CapabilityCatalog"
        }) {
            assertFalse(forbidden, build.contains(forbidden));
            assertFalse(forbidden, source.contains(forbidden));
        }
        assertFalse(source.contains("AndroidWorker"));
        assertFalse(source.contains("WorkerLifecycle"));
        assertTrue(source.contains("WorkerEventDefinition"));
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
