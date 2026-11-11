package com.xa.mass.android.workerdemo;

import static org.junit.Assert.*;

import android.content.Intent;
import android.net.Uri;
import android.widget.Button;
import android.widget.TextView;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/** Failed configuration must leave a repairable App rather than an anonymous Worker. */
@RunWith(RobolectricTestRunner.class)
@Config(application = AndroidConnectionStartupTest.UnreadableSettingsApplication.class, sdk = 33)
public class AndroidConnectionStartupTest {

    public static class UnreadableSettingsApplication extends AndroidWorkerDemoApplication {
        private byte[] saved;

        @Override AndroidConnectionSettingsStore createConnectionSettingsStore() {
            return new AndroidConnectionSettingsStore(new AndroidConnectionSettingsStore.FileAccess() {
                @Override public byte[] read() throws IOException {
                    throw new IOException("private configuration material must not reach diagnostics");
                }
                @Override public void write(byte[] value) { saved = value.clone(); }
            }, new AndroidConnectionSettingsStore.SecretCipher() {
                @Override public byte[] encrypt(byte[] value) { return value.clone(); }
                @Override public byte[] decrypt(byte[] value) { throw new IllegalStateException("unavailable"); }
            });
        }
    }

    public static class UnsupportedEndpointApplication extends AndroidWorkerDemoApplication {
        @Override AndroidConnectionSettingsStore createConnectionSettingsStore() {
            AndroidConnectionSettings saved = AndroidConnectionSettings.authenticated(
                    "https://[fe80::1%25wlan0]", "fixture-worker", "fixture-secret");
            return new AndroidConnectionSettingsStore(new AndroidConnectionSettingsStore.FileAccess() {
                @Override public byte[] read() { return new byte[]{1}; }
                @Override public void write(byte[] ignored) { }
            }, new AndroidConnectionSettingsStore.SecretCipher() {
                @Override public byte[] encrypt(byte[] plaintext) { return plaintext.clone(); }
                @Override public byte[] decrypt(byte[] ignored) { return saved.encodeForStorage(); }
            });
        }
    }

    @Test @Config(application = UnsupportedEndpointApplication.class)
    public void concreteClientAddressRejectionDoesNotCrashTheRepairUi() {
        AndroidWorkerDemoApplication app = (AndroidWorkerDemoApplication) RuntimeEnvironment.getApplication();
        assertNotNull(app.savedConnectionSettings());
        assertNull(app.activeConnectionSettings());
        assertFalse(app.hasWorker());
        assertFalse(app.capabilityHttpRunning());
        assertNotNull(app.connectionStartupError());
        assertFalse(app.connectionStartupError().contains("fixture-secret"));
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertTrue(controller.get().findViewById(R.id.connectionSettingsButton).isEnabled());
            assertFalse(controller.get().findViewById(R.id.connectButton).isEnabled());
        }
    }

    @Test public void unreadableConfigurationLeavesMainActivityRepairableWithoutWorkerOrHttp() {
        UnreadableSettingsApplication app = app();
        assertFalse(app.hasWorker());
        assertFalse(app.capabilityHttpRunning());
        assertNull(app.capabilityHttpEndpoint());
        assertNull(app.activeConnectionSettings());
        assertNotNull(app.connectionStartupError());
        assertFalse(app.connectionStartupError().contains("private configuration material"));
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            assertFalse(((Button) activity.findViewById(R.id.connectButton)).isEnabled());
            assertFalse(((Button) activity.findViewById(R.id.disconnectButton)).isEnabled());
            assertFalse(activity.findViewById(R.id.copyWorkerIdButton).isEnabled());
            assertTrue(activity.findViewById(R.id.connectionSettingsButton).isEnabled());
            assertEquals("CONFIGURATION UNAVAILABLE", ((TextView) activity.findViewById(R.id.statusValue)).getText().toString());
            controller.recreate();
            assertFalse(app.hasWorker());
        }
    }

    @Test public void repairedSavedValueWaitsForProcessRestartAndDefaultRestoreKeepsFailureFence() {
        UnreadableSettingsApplication app = app();
        AndroidConnectionSettings replacement = AndroidConnectionSettings.authenticated(
                "https://workers.example.test", "fixture-worker", "fixture-secret-not-a-real-key");
        app.saveConnectionSettings(replacement);
        assertNotNull(app.saved);
        assertSame(replacement, app.savedConnectionSettings());
        assertNull(app.savedConnectionError());
        assertNull(app.activeConnectionSettings());
        assertTrue(app.connectionRestartRequired());
        assertFalse(app.hasWorker());
        assertFalse(app.capabilityHttpRunning());
        app.restoreDefaultConnectionSettings();
        assertNull(app.savedConnectionSettings());
        assertTrue(app.connectionRestartRequired());
        assertFalse(app.hasWorker());
    }

    @Test public void oldNotificationCanOpenEmptyInboxWithoutRecoveringReporterOrWorker() {
        UnreadableSettingsApplication app = app();
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

    private static UnreadableSettingsApplication app() {
        return (UnreadableSettingsApplication) RuntimeEnvironment.getApplication();
    }
}
