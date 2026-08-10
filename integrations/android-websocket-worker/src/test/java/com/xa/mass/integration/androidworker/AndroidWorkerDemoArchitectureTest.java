package com.xa.mass.integration.androidworker;

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
    public void appOnlyHostsAndroidWorkerAndDemoCapability()
            throws IOException {
        Path project = Path.of("").toAbsolutePath();
        String build = read(project.resolve("build.gradle"));
        String source = readTree(project.resolve("src/main/java"));
        String application = readSource(
                project,
                "AndroidWorkerDemoApplication.java"
        );
        String activity = readSource(project, "MainActivity.java");
        String host = readSource(project, "AndroidWorkerDemoHost.java");
        String capability = readSource(
                project,
                "AndroidDemoStateCapability.java"
        );
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
                "project(':transport:android-worker')"
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

        assertTrue(application.contains(
                "AndroidWorker.builder"
        ));
        assertTrue(application.contains(
                "new AndroidDemoStateCapability"
        ));
        assertTrue(application.contains(
                "new AndroidWorkerExecutionResources"
        ));
        assertTrue(application.contains(".executionResources("));
        assertTrue(host.contains("executionResources.close()"));
        assertTrue(application.contains("host.start();"));
        assertFalse(source.contains("retryScheduler"));
        assertFalse(source.contains("ScheduledExecutorService"));
        assertTrue(activity.contains("workerHost.addListener"));
        assertTrue(activity.contains("workerHost.removeListener"));
        assertFalse(activity.contains("AndroidWorker.builder"));
        assertFalse(activity.contains("OkHttpWorkerControlClient"));
        assertFalse(activity.contains("WorkerEventDefinition"));
        assertFalse(activity.contains("workerHost.close()"));
        assertFalse(section(
                activity,
                "protected void onStop()",
                "private void bindViews()"
        ).contains("workerHost.stop()"));

        for (String forbidden : new String[]{
                "WorkerControlClient",
                "TextMessageClient",
                "WorkerEventDefinition",
                ".register(",
                ".bind("
        }) {
            assertFalse(forbidden, host.contains(forbidden));
        }
        for (String forbidden : new String[]{
                "workerId",
                "endpointUri",
                "MainActivity",
                "OkHttpWorkerControlClient"
        }) {
            assertFalse(forbidden, capability.contains(forbidden));
        }
        assertFalse(source.contains("AndroidWorkerIdentityStore"));
        assertFalse(source.contains("AndroidWorkerEndpointCacheStore"));
        assertFalse(source.contains("AndroidWorkerDemoController"));
        assertFalse(source.contains("AndroidDemoStateStore"));
        assertFalse(source.contains("AndroidDemoEvents"));
        assertFalse(source.contains("AndroidWebSocketWorkerRuntime"));
    }

    private static String readSource(Path project, String fileName)
            throws IOException {
        return read(project.resolve(
                "src/main/java/com/xa/mass/integration/androidworker/"
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
