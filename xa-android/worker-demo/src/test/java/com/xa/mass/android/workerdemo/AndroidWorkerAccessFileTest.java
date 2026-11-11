package com.xa.mass.android.workerdemo;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import com.xa.mass.worker.runtime.WorkerConnectionOptions;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class AndroidWorkerAccessFileTest {
    private static final URI RUNTIME = URI.create("https://workers.example.test");
    private static final String KEY = "file-fixture-host";
    private static final String SECRET = "file-fixture-secret-not-real";

    @Test public void privateFileProvidesOnlyGatewayOptionsForTheBuildOrigin() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File path = new File(context.getNoBackupFilesDir(), "worker-access.json");
        Files.write(path.toPath(), document("https://WORKERS.EXAMPLE.TEST:443/", KEY, SECRET));
        WorkerConnectionOptions result = AndroidWorkerAccessFile.load(context, RUNTIME, true);
        assertEquals(KEY, result.gatewayCredential().keyId());
        assertEquals(header(KEY, SECRET), result.gatewayCredential().authorizationHeader());
        assertFalse(result.toString().contains(SECRET));
        assertFalse(result.gatewayCredential().toString().contains(SECRET));
    }

    @Test public void anonymousBuildIgnoresPrivateFileAndDoesNotResolveItsPath() {
        Context context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public File getNoBackupFilesDir() { throw new AssertionError("Anonymous build read private location"); }
        };
        assertNull(AndroidWorkerAccessFile.load(context, URI.create("http://127.0.0.1:18182"), false).gatewayCredential());
        assertNull(AndroidWorkerAccessFile.load(RUNTIME, false, () -> {
            throw new AssertionError("Anonymous build attempted credential I/O");
        }).gatewayCredential());
    }

    @Test public void missingAndUnreadableRequiredFileFailWithOnlySafeDiagnostics() {
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> AndroidWorkerAccessFile.load(RuntimeEnvironment.getApplication(), RUNTIME, true));
        assertNull(missing.getCause());
        IllegalStateException readError = assertThrows(IllegalStateException.class,
                () -> AndroidWorkerAccessFile.load(RUNTIME, true, () -> {
                    throw new IOException("private-path-and-" + SECRET);
                }));
        assertEquals(missing.getMessage(), readError.getMessage());
        assertFalse(readError.getMessage().contains(SECRET));
        assertNull(readError.getCause());
    }

    @Test public void alternateHttpsPortAndExistingBuildPathAreSupported() {
        WorkerConnectionOptions result = load(URI.create("https://workers.example.test:8443/base"),
                document("https://workers.example.test:8443", KEY, SECRET));
        assertEquals(KEY, result.gatewayCredential().keyId());
    }

    @Test public void differentOriginNeverProducesConnectionOptions() {
        for (String origin : new String[]{
                "https://other.example.test", "https://workers.example.test:8443", "http://workers.example.test"}) {
            rejected(RUNTIME, document(origin, KEY, SECRET));
        }
        rejected(URI.create("https://workers.example.test:8443"), document(RUNTIME.toString(), KEY, SECRET));
    }

    @Test public void allowedOriginMustBeAnHttpsOriginWithoutEmbeddedRequestData() {
        for (String origin : new String[]{
                "https://workers.example.test/path", "https://workers.example.test?x=1",
                "https://workers.example.test#part", "https://user@workers.example.test",
                "https://workers.example.test:0", "https://workers.example.test:65536",
                "https:workers.example.test", " https://workers.example.test"}) {
            rejected(RUNTIME, document(origin, KEY, SECRET));
        }
    }

    @Test public void buildRuntimeAndCredentialsStillUseSdkAdmission() {
        for (String runtime : new String[]{
                "http://workers.example.test", "https://user@workers.example.test",
                "https://workers.example.test?x=1", "https://workers.example.test#part"}) {
            rejected(URI.create(runtime), document(RUNTIME.toString(), KEY, SECRET));
        }
        rejected(RUNTIME, document(RUNTIME.toString(), "bad:key", SECRET));
        rejected(RUNTIME, document(RUNTIME.toString(), KEY, " "));
        rejected(RUNTIME, document(RUNTIME.toString(), KEY, "secret\nline"));
    }

    @Test public void malformedDuplicateUnknownAndIncompleteObjectsAreRejected() {
        String valid = new String(document(RUNTIME.toString(), KEY, SECRET), StandardCharsets.UTF_8);
        for (String value : new String[]{
                "", "{}", "[]", "null", "false", "{", valid + " {}", valid + " trailing",
                valid.replace("\"secret\":", "\"unknown\":"),
                valid.substring(0, valid.length() - 1) + ",\"keyId\":\"duplicate\"}",
                "{\"allowedOrigin\":\"https://workers.example.test\",\"keyId\":42,\"secret\":\"fixture\"}",
                "{\"allowedOrigin\":\"https://workers.example.test\",\"keyId\":\"fixture\",\"secret\":null}",
                "{allowedOrigin:'https://workers.example.test',keyId:'fixture',secret:'fixture'}"}) {
            rejected(RUNTIME, value.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test public void invalidUtf8AndOversizeInputFailBeforeAConnectionIsCreated() {
        rejected(RUNTIME, new byte[]{'{', (byte) 0xc3, (byte) 0x28, '}'});
        byte[] oversized = new byte[AndroidWorkerAccessFile.MAX_BYTES + 1];
        java.util.Arrays.fill(oversized, (byte) ' ');
        rejected(RUNTIME, oversized);
        byte[] valid = document(RUNTIME.toString(), KEY, SECRET);
        byte[] exact = java.util.Arrays.copyOf(valid, AndroidWorkerAccessFile.MAX_BYTES);
        java.util.Arrays.fill(exact, valid.length, exact.length, (byte) ' ');
        assertEquals(KEY, load(RUNTIME, exact).gatewayCredential().keyId());
    }

    @Test public void returnedSnapshotDoesNotFollowLaterFileChanges() {
        AtomicReference<byte[]> current = new AtomicReference<>(document(RUNTIME.toString(), KEY, SECRET));
        AtomicInteger reads = new AtomicInteger();
        AndroidWorkerAccessFile.Input file = () -> {
            reads.incrementAndGet();
            return new ByteArrayInputStream(current.get());
        };
        WorkerConnectionOptions first = AndroidWorkerAccessFile.load(RUNTIME, true, file);
        current.set(document(RUNTIME.toString(), "replacement-host", "replacement-fixture"));
        assertEquals(1, reads.get());
        assertEquals(header(KEY, SECRET), first.gatewayCredential().authorizationHeader());
        WorkerConnectionOptions nextStartup = AndroidWorkerAccessFile.load(RUNTIME, true, file);
        assertEquals(2, reads.get());
        assertEquals("replacement-host", nextStartup.gatewayCredential().keyId());
    }

    private static WorkerConnectionOptions load(URI runtime, byte[] bytes) {
        return AndroidWorkerAccessFile.load(runtime, true, () -> new ByteArrayInputStream(bytes));
    }

    private static void rejected(URI runtime, byte[] bytes) {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> load(runtime, bytes));
        assertEquals("Required Worker gateway credential file is unavailable or invalid", failure.getMessage());
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains(SECRET));
    }

    private static byte[] document(String origin, String keyId, String secret) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("allowedOrigin", origin);
        value.put("keyId", keyId);
        value.put("secret", secret);
        return Jsons.toJson(value).getBytes(StandardCharsets.UTF_8);
    }

    private static String header(String key, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((key + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }
}
