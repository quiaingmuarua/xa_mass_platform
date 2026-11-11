package com.xa.mass.android.workerdemo;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = ConnectionSettingsActivityTest.UnreadableSettingsApplication.class, sdk = 33)
public class ConnectionSettingsActivityTest {
    private static final String URL = "https://worker.example.test";
    private static final String KEY = "ui-fixture-host";
    private static final String SECRET = "ui-fixture-secret-not-for-real-use";
    private UnreadableSettingsApplication app;
    private ActivityController<ConnectionSettingsActivity> controller;

    @Before public void openUnavailableConnection() {
        app = (UnreadableSettingsApplication) RuntimeEnvironment.getApplication();
        assertFalse(app.hasWorker());
        assertFalse(app.capabilityHttpRunning());
        assertNotNull(app.connectionStartupError());
        controller = Robolectric.buildActivity(ConnectionSettingsActivity.class).setup();
    }

    @After public void closeScreen() {
        if (controller != null) controller.close();
    }

    @Test public void unavailableStartupStillHasPrivateConfigurationUi() throws Exception {
        assertEquals(View.VISIBLE, view(R.id.connectionStartupError).getVisibility());
        assertTrue(text(R.id.connectionGroupValue).contains("android-demo-workers"));
        assertFalse(app.getPackageManager().getActivityInfo(
                new ComponentName(app, ConnectionSettingsActivity.class), 0).exported);
        assertEquals("", input(R.id.connectionSecretInput).getText().toString());
        assertFalse(input(R.id.connectionSecretInput).isSaveEnabled());
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
                input(R.id.connectionSecretInput).getImportantForAutofill());
        assertEquals(0, app.files.writes);
    }

    @Test public void completeSavePersistsOnlyNextStartupAndNeverRefillsSecret() {
        fill(URL, KEY, SECRET);
        click(R.id.saveConnectionSettingsButton);
        AndroidConnectionSettings expected = AndroidConnectionSettings.authenticated(URL, KEY, SECRET);
        assertTrue(app.store.load().sameConnection(expected));
        assertTrue(app.savedConnectionSettings().sameConnection(expected));
        assertNull(app.activeConnectionSettings());
        assertFalse(app.hasWorker());
        assertFalse(app.capabilityHttpRunning());
        assertTrue(app.connectionRestartRequired());
        assertTrue(text(R.id.connectionSavedValue).contains(URL));
        assertTrue(text(R.id.connectionPendingValue).contains("完整重启"));
        assertEquals("", input(R.id.connectionSecretInput).getText().toString());

        controller.recreate();
        assertEquals(URL, input(R.id.connectionRuntimeInput).getText().toString());
        assertEquals(KEY, input(R.id.connectionKeyIdInput).getText().toString());
        assertEquals("", input(R.id.connectionSecretInput).getText().toString());
        assertFalse(text(R.id.connectionSavedValue).contains(SECRET));

        input(R.id.connectionRuntimeInput).setText("https://different.example.test");
        click(R.id.saveConnectionSettingsButton);
        assertNotNull(input(R.id.connectionSecretInput).getError());
        assertEquals(1, app.files.writes);
        assertTrue(app.savedConnectionSettings().sameConnection(expected));
    }

    @Test public void incompleteAndPlaintextRequestsNeverReachStorage() {
        fill("", KEY, SECRET);
        click(R.id.saveConnectionSettingsButton);
        assertNotNull(input(R.id.connectionRuntimeInput).getError());
        fill(URL, "", SECRET);
        click(R.id.saveConnectionSettingsButton);
        assertNotNull(input(R.id.connectionKeyIdInput).getError());
        fill(URL, KEY, " ");
        click(R.id.saveConnectionSettingsButton);
        assertNotNull(input(R.id.connectionSecretInput).getError());
        fill("http://worker.example.test", KEY, SECRET);
        click(R.id.saveConnectionSettingsButton);
        assertEquals(controller.get().getString(R.string.connection_invalid_input), text(R.id.connectionFeedback));
        assertFalse(text(R.id.connectionFeedback).contains(SECRET));
        assertEquals(0, app.files.writes);
        assertNull(app.savedConnectionSettings());
        assertFalse(app.hasWorker());
    }

    @Test public void failedSavePreservesSavedValueAndDoesNotExposeCause() {
        fill(URL, KEY, SECRET);
        click(R.id.saveConnectionSettingsButton);
        AndroidConnectionSettings previous = app.savedConnectionSettings();
        app.files.failWrites = true;
        fill("https://replacement.example.test", "replacement-host", "replacement-ui-secret");
        click(R.id.saveConnectionSettingsButton);
        assertTrue(app.store.load().sameConnection(previous));
        assertTrue(app.savedConnectionSettings().sameConnection(previous));
        assertNotNull(app.savedConnectionError());
        assertEquals(controller.get().getString(R.string.connection_save_failed), text(R.id.connectionFeedback));
        assertFalse(text(R.id.connectionFeedback).contains("private-failure-detail"));
        assertFalse(text(R.id.connectionFeedback).contains("replacement-ui-secret"));
        assertNull(app.activeConnectionSettings());
        assertFalse(app.hasWorker());
    }

    @Test public void publicationResponseLossDoesNotPresentOldCacheAsConfirmedSavedState() {
        fill(URL, KEY, SECRET);
        click(R.id.saveConnectionSettingsButton);
        AndroidConnectionSettings previous = app.savedConnectionSettings();
        app.files.failAfterPublication = true;
        AndroidConnectionSettings replacement = AndroidConnectionSettings.authenticated(
                "https://replacement.example.test", "replacement-host", "replacement-ui-secret");
        fill("https://replacement.example.test", "replacement-host", "replacement-ui-secret");
        click(R.id.saveConnectionSettingsButton);
        assertTrue(app.store.load().sameConnection(replacement));
        assertTrue(app.savedConnectionSettings().sameConnection(previous));
        assertNotNull(app.savedConnectionError());
        assertEquals(controller.get().getString(R.string.connection_pending_unavailable), text(R.id.connectionPendingValue));
        assertEquals(2, app.files.writes);
        assertNull(app.activeConnectionSettings());
        assertFalse(app.hasWorker());
        assertFalse(text(R.id.connectionSavedValue).contains("replacement-ui-secret"));
    }

    @Test public void restoreDefaultIsSavedForRestartWithoutStartingWorker() {
        fill(URL, KEY, SECRET);
        click(R.id.saveConnectionSettingsButton);
        click(R.id.restoreConnectionDefaultsButton);
        assertNull(app.store.load());
        assertNull(app.savedConnectionSettings());
        assertNull(app.savedConnectionError());
        assertEquals(app.defaultConnectionSettings().runtimeApiBaseUrl().toString(),
                input(R.id.connectionRuntimeInput).getText().toString());
        assertEquals("", input(R.id.connectionKeyIdInput).getText().toString());
        assertEquals("", input(R.id.connectionSecretInput).getText().toString());
        assertTrue(app.connectionRestartRequired());
        assertNull(app.activeConnectionSettings());
        assertFalse(app.hasWorker());
        assertFalse(app.capabilityHttpRunning());
    }

    @Test public void rotationAndLeavingTheActivityDiscardSecretWithoutSaving() {
        fill(URL, KEY, SECRET);
        Bundle state = new Bundle();
        controller.saveInstanceState(state);
        assertEquals("", input(R.id.connectionSecretInput).getText().toString());
        controller.recreate();
        assertEquals("", input(R.id.connectionSecretInput).getText().toString());
        assertEquals(app.defaultConnectionSettings().runtimeApiBaseUrl().toString(),
                input(R.id.connectionRuntimeInput).getText().toString());
        input(R.id.connectionSecretInput).setText(SECRET);
        controller.pause();
        assertEquals("", input(R.id.connectionSecretInput).getText().toString());
        controller.resume();
        assertEquals(0, app.files.writes);
        assertNull(app.savedConnectionSettings());
    }

    @Test public void reopenedEditorUsesRestoredDefaultsInsteadOfTheOldActiveConnection() {
        fill(URL, KEY, SECRET);
        click(R.id.saveConnectionSettingsButton);
        app.activeForUi = app.savedConnectionSettings();
        click(R.id.restoreConnectionDefaultsButton);
        controller.recreate();
        assertEquals(app.defaultConnectionSettings().runtimeApiBaseUrl().toString(),
                input(R.id.connectionRuntimeInput).getText().toString());
        assertEquals("", input(R.id.connectionKeyIdInput).getText().toString());
        assertEquals("", input(R.id.connectionSecretInput).getText().toString());
        assertTrue(text(R.id.connectionEffectiveValue).contains(URL));
    }

    @Test public void applicationInfoUsesOnlyThisPackageAndClearsDraftSecret() {
        fill(URL, KEY, SECRET);
        click(R.id.openApplicationInfoButton);
        Intent intent = shadowOf(controller.get()).getNextStartedActivity();
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.getAction());
        assertEquals("package:" + app.getPackageName(), intent.getDataString());
        assertNull(intent.getExtras());
        assertEquals("", input(R.id.connectionSecretInput).getText().toString());
        assertEquals(0, app.files.writes);
    }

    private void fill(String url, String keyId, String secret) {
        input(R.id.connectionRuntimeInput).setText(url);
        input(R.id.connectionKeyIdInput).setText(keyId);
        input(R.id.connectionSecretInput).setText(secret);
    }

    private View view(int id) { return controller.get().findViewById(id); }
    private EditText input(int id) { return (EditText) view(id); }
    private String text(int id) { return ((TextView) view(id)).getText().toString(); }
    private void click(int id) { assertTrue(view(id).performClick()); }

    public static final class UnreadableSettingsApplication extends AndroidWorkerDemoApplication {
        final MemoryFiles files = new MemoryFiles();
        final AndroidConnectionSettingsStore store = new AndroidConnectionSettingsStore(files, new UiCipher());
        AndroidConnectionSettings activeForUi;

        @Override AndroidConnectionSettingsStore createConnectionSettingsStore() { return store; }
        @Override AndroidConnectionSettings activeConnectionSettings() {
            return activeForUi == null ? super.activeConnectionSettings() : activeForUi;
        }
    }

    private static final class MemoryFiles implements AndroidConnectionSettingsStore.FileAccess {
        byte[] saved = {0};
        boolean failWrites;
        boolean failAfterPublication;
        int writes;
        @Override public byte[] read() { return saved.clone(); }
        @Override public void write(byte[] bytes) throws IOException {
            if (failWrites) throw new IOException("private-failure-detail");
            saved = bytes.clone();
            writes++;
            if (failAfterPublication) throw new IOException("unconfirmed-private-publication-detail");
        }
    }

    /** A reversible UI fixture; Android Keystore encryption has separate store/device proof. */
    private static final class UiCipher implements AndroidConnectionSettingsStore.SecretCipher {
        @Override public byte[] encrypt(byte[] plaintext) {
            byte[] result = new byte[plaintext.length + 1];
            result[0] = 42;
            for (int i = 0; i < plaintext.length; i++) result[i + 1] = (byte) (plaintext[i] ^ 0x55);
            return result;
        }
        @Override public byte[] decrypt(byte[] encrypted) throws GeneralSecurityException {
            if (encrypted.length < 2 || encrypted[0] != 42) throw new GeneralSecurityException();
            byte[] result = Arrays.copyOfRange(encrypted, 1, encrypted.length);
            for (int i = 0; i < result.length; i++) result[i] ^= 0x55;
            return result;
        }
    }
}
