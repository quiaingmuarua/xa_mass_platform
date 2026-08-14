package com.xa.mass.android.capabilityhttp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.Test;

public final class CapabilityHttpArchitectureTest {

    @Test
    public void staysAWorkerCoreOnlyLoopbackProbe() throws IOException {
        Path project = Path.of("").toAbsolutePath();
        String build = read(project.resolve("build.gradle"));
        String source = readTree(project.resolve("src/main/java"));
        String facade = read(project.resolve(
                "src/main/java/com/xa/mass/android/capabilityhttp/"
                        + "AndroidCapabilityHttpServer.java"
        ));

        assertTrue(build.contains("project(':transport:worker-core')"));
        assertTrue(build.contains(
                "implementation 'org.nanohttpd:nanohttpd:2.3.1'"
        ));
        assertFalse(build.contains("api 'org.nanohttpd"));
        assertFalse(build.contains("gson"));
        assertTrue(source.contains("super(hostname, port)"));
        assertTrue(source.contains("WorkerCommandDispatcher.forWorker"));
        assertFalse(facade.contains("extends NanoHTTPD"));

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
                "WorkerEventCatalog",
                "WorkerEventDescriptor",
                "WorkerEventDispatcher",
                "com.xa.mass.transport.android.http",
                "AgentForge"
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
