package com.xa.mass.android.workerdemo;

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
public class AndroidWorkerDemoArchitectureTest {

    @Test
    public void appOnlyHostsAndroidWorkerAndDemoCapabilities()
            throws IOException {
        Path project = Path.of("").toAbsolutePath();
        String build = read(project.resolve("build.gradle"));
        String source = readTree(project.resolve("src/main/java"));
        String application = readSource(
                project,
                "AndroidWorkerDemoApplication.java"
        );
        String activity = readSource(project, "MainActivity.java");
        String mainManifest = read(project.resolve(
                "src/main/AndroidManifest.xml"
        ));
        String debugManifest = read(project.resolve(
                "src/debug/AndroidManifest.xml"
        ));
        String backupRules = read(project.resolve(
                "src/main/res/xml/backup_rules.xml"
        ));
        String dataExtractionRules = read(project.resolve(
                "src/main/res/xml/data_extraction_rules.xml"
        ));

        assertTrue(build.contains("id 'com.android.application'"));
        assertTrue(build.contains(
                "namespace = 'com.xa.mass.android.workerdemo'"
        ));
        assertTrue(build.contains(
                "applicationId = 'com.xa.mass.integration.androidworker'"
        ));
        assertTrue(build.contains(
                "project(':transport:android-worker')"
        ));
        assertTrue(build.contains(
                "project(':xa-android:capabilities')"
        ));
        assertFalse(build.contains("project(':transport:worker-core')"));
        assertFalse(build.contains("project(':transport:java-worker')"));
        assertTrue(mainManifest.contains("android.permission.INTERNET"));
        assertTrue(mainManifest.contains(
                "android:name=\".AndroidWorkerDemoApplication\""
        ));
        assertTrue(mainManifest.contains(
                "android:dataExtractionRules=\"@xml/data_extraction_rules\""
        ));
        assertTrue(mainManifest.contains(
                "android:fullBackupContent=\"@xml/backup_rules\""
        ));
        assertFalse(mainManifest.contains("usesCleartextTraffic"));
        assertTrue(debugManifest.contains(
                "android:usesCleartextTraffic=\"true\""
        ));
        assertTrue(backupRules.contains(
                "path=\"xa-mass-android-worker.xml\""
        ));
        assertTrue(dataExtractionRules.contains(
                "path=\"xa-mass-android-worker.xml\""
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
                "WorkManager"
        }) {
            assertFalse(forbidden, build.contains(forbidden));
            assertFalse(forbidden, source.contains(forbidden));
        }

        assertTrue(application.contains("private AndroidWorker worker;"));
        assertTrue(application.contains(
                "private AndroidDemoCapabilities demoCapabilities;"
        ));
        assertTrue(application.contains("AndroidWorker.create("));
        assertFalse(application.contains(
                "AndroidWorker." + "builder("
        ));
        assertTrue(application.contains(
                "demoCapabilities.definitions()"
        ));
        assertTrue(application.contains(
                "new AndroidDemoCapabilities("
        ));
        assertTrue(application.contains("worker.start();"));
        assertFalse(Files.exists(project.resolve(
                "src/main/java/com/xa/mass/android/workerdemo/"
                        + "AndroidWorker" + "Demo.java"
        )));
        assertFalse(source.contains("HostResources"));
        assertFalse(source.contains("controlExecutor"));
        assertFalse(source.contains("java.util.concurrent.Executor"));
        assertFalse(source.contains("retryScheduler"));
        assertFalse(source.contains("ScheduledExecutorService"));
        assertFalse(source.contains("WorkerExecutionResources"));
        assertTrue(activity.contains("worker.addListener(workerListener)"));
        assertTrue(activity.contains(
                "demoCapabilities.addListener(capabilityListener)"
        ));
        assertTrue(activity.contains("runOnUiThread(this::render)"));
        assertFalse(activity.contains("AndroidWorker.builder"));
        assertFalse(activity.contains("OkHttpWorkerControlClient"));
        assertFalse(activity.contains("WorkerEventDefinition"));
        assertFalse(activity.contains("worker.close()"));
        assertFalse(section(
                activity,
                "protected void onStop()",
                "private void bindViews()"
        ).contains("worker.stop()"));

        for (String forbidden : new String[]{
                "WorkerControlClient",
                "TextMessageClient",
                "WorkerEventDefinition",
                ".register(",
                ".bind("
        }) {
            assertFalse(forbidden, application.contains(forbidden));
        }
        assertFalse(source.contains("AndroidWorkerIdentityStore"));
        assertFalse(source.contains("AndroidWorkerEndpointCacheStore"));
        assertFalse(source.contains("AndroidDemoStateStore"));
        assertFalse(source.contains(
                "AndroidDemoState" + "Capability"
        ));
        assertFalse(source.contains(
                "package com.xa.mass.integration." + "androidworker"
        ));
        assertFalse(source.contains("AndroidDemoEvents"));
        assertFalse(source.contains("AndroidWebSocketWorkerRuntime"));
    }

    private static String readSource(Path project, String fileName)
            throws IOException {
        return read(project.resolve(
                "src/main/java/com/xa/mass/android/workerdemo/"
                        + fileName
        ));
    }

    private static String section(
            String source,
            String startMarker,
            String endMarker
    ) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        if (start < 0 || end < 0) {
            throw new IllegalArgumentException(
                    "Unable to locate source section"
            );
        }
        return source.substring(start, end);
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
