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
    public void applicationOwnsWorkerAndActivityOnlyControlsIt()
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
        String mainValues = read(project.resolve(
                "src/main/res/values/strings.xml"
        ));
        String backupRules = read(project.resolve(
                "src/main/res/xml/backup_rules.xml"
        ));
        String dataExtractionRules = read(project.resolve(
                "src/main/res/xml/data_extraction_rules.xml"
        ));

        assertTrue(build.contains("id 'com.android.application'"));
        assertTrue(build.contains(
                "implementation project(':transport:android-worker')"
        ));
        assertTrue(build.contains(
                "implementation project(':xa-android:capabilities')"
        ));
        assertTrue(build.contains(
                "implementation project(':xa-android:capability-http')"
        ));
        assertFalse(build.contains("project(':transport:worker-core')"));
        assertFalse(build.contains("project(':transport:java-worker')"));
        for (int index = 1; index <= 3; index++) {
            assertTrue(build.contains("lab" + index + " {"));
            assertTrue(build.contains(
                    "applicationIdSuffix = '.lab" + index + "'"
            ));
            assertTrue(read(project.resolve(
                    "src/lab" + index + "/AndroidManifest.xml"
            )).contains("android:usesCleartextTraffic=\"true\""));
            String labValues = read(project.resolve(
                    "src/lab" + index + "/res/values/lab.xml"
            ));
            assertTrue(labValues.contains(
                    ">XA Mass Android Worker Lab " + index + "<"
            ));
            assertTrue(labValues.contains(
                    ">" + (18_183 + index) + "</integer>"
            ));
            assertTrue(labValues.contains(
                    ">http://127.0.0.1:" + (18_183 + index) + "</string>"
            ));
        }

        assertTrue(mainManifest.contains("android.permission.INTERNET"));
        assertTrue(mainManifest.contains(
                "android:name=\".AndroidWorkerDemoApplication\""
        ));
        assertTrue(mainManifest.contains("android:label=\"@string/app_name\""));
        assertFalse(mainManifest.contains("usesCleartextTraffic"));
        assertTrue(debugManifest.contains(
                "android:usesCleartextTraffic=\"true\""
        ));
        assertTrue(mainValues.contains(
                "<integer name=\"capability_http_port\">18084</integer>"
        ));
        assertTrue(mainValues.contains(
                ">http://127.0.0.1:18084</string>"
        ));
        assertTrue(backupRules.contains(
                "path=\"xa-mass-android-worker.xml\""
        ));
        assertTrue(dataExtractionRules.contains(
                "path=\"xa-mass-android-worker.xml\""
        ));

        assertTrue(Application.class.isAssignableFrom(
                AndroidWorkerDemoApplication.class
        ));
        assertTrue(application.contains("AndroidWorker.create("));
        assertTrue(application.contains("new AndroidWorkerLabEvents()"));
        assertTrue(application.contains("AndroidCapabilityHttpServer.create("));
        assertTrue(application.contains(
                "R.integer.capability_http_port"
        ));
        assertTrue(application.contains("worker.start();"));
        assertTrue(application.contains("worker.close();"));

        assertFalse(activity.contains("AndroidWorker.create("));
        assertFalse(activity.contains("AndroidCapabilityHttpServer"));
        assertFalse(activity.contains("WorkerControlClient"));
        assertFalse(activity.contains("TextMessageClient"));
        assertFalse(activity.contains("worker.close()"));
        assertFalse(section(
                activity,
                "protected void onStop()",
                "private void bindViews()"
        ).contains("worker.stop()"));

        for (String forbidden : new String[]{
                "server_jvm",
                "kernel_jvm",
                "scenario_workers_jvm",
                "transport:netty-adapter",
                "com.xa.mass.server",
                "com.xa.mass.worker.javase",
                "io.netty",
                "org.springframework",
                "HostResources",
                "WorkerExecutionResources"
        }) {
            assertFalse(forbidden, source.contains(forbidden));
        }
    }

    private static String readSource(Path project, String fileName)
            throws IOException {
        return read(project.resolve(
                "src/main/java/com/xa/mass/android/workerdemo/" + fileName
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
            throw new IllegalArgumentException("Unable to locate source section");
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
