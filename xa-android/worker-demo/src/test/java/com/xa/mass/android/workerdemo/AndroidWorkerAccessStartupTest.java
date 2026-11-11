package com.xa.mass.android.workerdemo;

import static org.junit.Assert.*;

import android.app.RemoteInput;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import com.xa.mass.worker.runtime.WorkerConnectionOptions;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = AndroidWorkerAccessStartupTest.MissingAccessApplication.class, sdk = 33)
public class AndroidWorkerAccessStartupTest {
    public static class MissingAccessApplication extends AndroidWorkerDemoApplication {
        int accessReads;
        byte[] repaired;

        @Override WorkerConnectionOptions loadWorkerConnectionOptions(URI runtime) {
            accessReads++;
            return AndroidWorkerAccessFile.load(URI.create("https://workers.example.test"), true, () -> {
                if (repaired == null) throw new IOException("private-file-material");
                return new ByteArrayInputStream(repaired);
            });
        }
    }

    public static class UnsupportedRuntimeApplication extends AndroidWorkerDemoApplication {
        private Resources testResources;
        WorkerConnectionOptions loadedOptions;

        @Override @SuppressWarnings("deprecation") public Resources getResources() {
            if (testResources == null) {
                Resources original = super.getResources();
                testResources = new Resources(original.getAssets(), original.getDisplayMetrics(), original.getConfiguration()) {
                    @Override public String getString(int id) throws NotFoundException {
                        return id == R.string.runtime_api_base_url
                                ? "https://[fe80::1%25wlan0]" : super.getString(id);
                    }
                };
            }
            return testResources;
        }

        @Override WorkerConnectionOptions loadWorkerConnectionOptions(URI runtime) {
            byte[] file = ("{\"allowedOrigin\":\"" + runtime + "\","
                    + "\"keyId\":\"fixture-host\",\"secret\":\"fixture-only-secret\"}").getBytes(StandardCharsets.UTF_8);
            loadedOptions = AndroidWorkerAccessFile.load(runtime, true, () -> new ByteArrayInputStream(file));
            return loadedOptions;
        }
    }

    @Test @Config(application = UnsupportedRuntimeApplication.class)
    public void concreteClientRejectionAfterCredentialReadLeavesSafeUnavailableUi() {
        UnsupportedRuntimeApplication app = (UnsupportedRuntimeApplication) RuntimeEnvironment.getApplication();
        assertNotNull("The file/shared SDK checks succeeded before concrete client construction", app.loadedOptions);
        assertEquals("fixture-host", app.loadedOptions.gatewayCredential().keyId());
        assertEquals("无法初始化 Worker 连接，请检查构建地址并完整重启 App。", app.connectionStartupError());
        assertFalse(app.connectionStartupError().contains("fixture-only-secret"));
        assertFalse(app.hasWorker());
        assertFalse(app.capabilityHttpRunning());
        assertNull(app.capabilityHttpEndpoint());
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertEquals("STARTUP UNAVAILABLE", ((TextView) controller.get().findViewById(R.id.statusValue)).getText().toString());
            assertFalse(controller.get().findViewById(R.id.connectButton).isEnabled());
            assertFalse(controller.get().findViewById(R.id.disconnectButton).isEnabled());
        }
    }

    @Test public void requiredFileFailureLeavesDiagnosticsButNoWorkerOrHttp() {
        MissingAccessApplication app = app();
        assertEquals(1, app.accessReads);
        assertFalse(app.hasWorker());
        assertFalse(app.capabilityHttpRunning());
        assertNull(app.capabilityHttpEndpoint());
        assertNotNull(app.connectionStartupError());
        assertFalse(app.connectionStartupError().contains("private-file-material"));
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            assertEquals("STARTUP UNAVAILABLE", ((TextView) activity.findViewById(R.id.statusValue)).getText().toString());
            assertFalse(activity.findViewById(R.id.connectButton).isEnabled());
            assertFalse(activity.findViewById(R.id.disconnectButton).isEnabled());
            assertFalse(activity.findViewById(R.id.copyWorkerIdButton).isEnabled());
            controller.recreate();
            assertEquals(1, app.accessReads);
            assertFalse(app.hasWorker());
        }
    }

    @Test public void repairingFileDoesNotReloadOrStartTheFailedProcess() {
        MissingAccessApplication app = app();
        app.repaired = ("{\"allowedOrigin\":\"https://workers.example.test\","
                + "\"keyId\":\"fixture-host\",\"secret\":\"fixture-only-secret\"}").getBytes(StandardCharsets.UTF_8);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            controller.get().findViewById(R.id.connectButton).performClick();
            controller.recreate();
            assertEquals(1, app.accessReads);
            assertFalse(app.hasWorker());
            assertFalse(app.capabilityHttpRunning());
        }
    }

    @Test public void oldNotificationCanOpenEmptyInboxWithoutRecoveringReporter() {
        MissingAccessApplication app = app();
        assertNotNull(app.messages());
        assertNotNull(app.messageNotifications());
        Intent oldOpen = new Intent(app, MessagesActivity.class)
                .setData(Uri.parse("xa-messages://open/previous-process-token"));
        try (ActivityController<MessagesActivity> controller = Robolectric.buildActivity(MessagesActivity.class, oldOpen).setup()) {
            assertTrue(app.messages().recentMessages().isEmpty());
            controller.recreate();
            assertFalse(app.hasWorker());
            assertFalse(app.capabilityHttpRunning());
        }
    }

    @Test public void oldNotificationReplyCannotRecreateAWorkerOrReporter() {
        MissingAccessApplication app = app();
        Intent oldReply = new Intent(app, MessageReplyReceiver.class)
                .setData(Uri.parse("xa-messages://reply/previous-process-token"));
        Bundle reply = new Bundle();
        reply.putCharSequence(AndroidMessageNotifications.REPLY, "fixed fixture reply");
        RemoteInput.addResultsToIntent(new RemoteInput[]{
                new RemoteInput.Builder(AndroidMessageNotifications.REPLY).build()}, oldReply, reply);
        new MessageReplyReceiver().onReceive(app, oldReply);
        assertTrue(app.messages().recentMessages().isEmpty());
        assertEquals(1, app.accessReads);
        assertFalse(app.hasWorker());
        assertFalse(app.capabilityHttpRunning());
    }

    private static MissingAccessApplication app() {
        return (MissingAccessApplication) RuntimeEnvironment.getApplication();
    }
}
