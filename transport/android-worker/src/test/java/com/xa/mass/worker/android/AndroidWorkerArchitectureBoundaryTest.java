package com.xa.mass.worker.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.xa.mass.worker.runtime.WorkerLifecycle;
import java.io.IOException;
import java.lang.reflect.Modifier;
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
        String manifest = read(project.resolve("src/main/AndroidManifest.xml"));

        assertTrue(build.contains("id 'com.android.library'"));
        assertTrue(build.contains("api project(':transport:worker-core')"));
        assertFalse(build.contains("project(':transport:java-worker')"));
        assertTrue(build.contains(
                "implementation 'com.squareup.okhttp3:okhttp:5.3.0'"
        ));
        assertTrue(build.contains("minSdk = 24"));
        assertTrue(manifest.contains("android.permission.INTERNET"));

        assertTrue(Modifier.isPublic(AndroidWorker.class.getModifiers()));
        assertTrue(Modifier.isFinal(AndroidWorker.class.getModifiers()));
        assertTrue(WorkerLifecycle.class.isAssignableFrom(AndroidWorker.class));
        assertTrue(Stream.of(AndroidWorker.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("create")
                        && Modifier.isPublic(method.getModifiers())
                        && Modifier.isStatic(method.getModifiers())));
        assertFalse(Stream.of(AndroidWorker.class.getDeclaredClasses())
                .anyMatch(type -> type.getSimpleName().equals("Builder")));
        assertFalse(Modifier.isPublic(
                AndroidWorkerPlatform.class.getModifiers()
        ));

        assertFalse(Files.exists(project.resolve(
                "src/main/java/com/xa/mass/worker/android/"
                        + "AndroidWorkerHostResources.java"
        )));
        assertFalse(Files.exists(project.resolve(
                "src/main/java/com/xa/mass/worker/android/"
                        + "AndroidWorkerEndpointCacheStore.java"
        )));

        for (String forbidden : new String[]{
                "server_jvm",
                "kernel_jvm",
                "transport:java-worker",
                "com.xa.mass.worker.javase",
                "io.netty",
                "org.springframework",
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
            throw new IllegalStateException("Unable to read " + path, error);
        }
    }

    private static String read(Path path) throws IOException {
        return new String(
                Files.readAllBytes(path),
                StandardCharsets.UTF_8
        );
    }
}
