package com.xa.mass.android.workerdemo;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

/** App-private connection editing; a saved value never replaces the current Worker run. */
public final class ConnectionSettingsActivity extends Activity {

    private AndroidWorkerDemoApplication application;
    private EditText runtimeInput;
    private EditText keyIdInput;
    private EditText secretInput;
    private TextView effectiveValue;
    private TextView savedValue;
    private TextView pendingValue;
    private TextView startupError;
    private TextView feedback;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connection_settings);
        application = (AndroidWorkerDemoApplication) getApplication();
        runtimeInput = findViewById(R.id.connectionRuntimeInput);
        keyIdInput = findViewById(R.id.connectionKeyIdInput);
        secretInput = findViewById(R.id.connectionSecretInput);
        effectiveValue = findViewById(R.id.connectionEffectiveValue);
        savedValue = findViewById(R.id.connectionSavedValue);
        pendingValue = findViewById(R.id.connectionPendingValue);
        startupError = findViewById(R.id.connectionStartupError);
        feedback = findViewById(R.id.connectionFeedback);
        ((TextView) findViewById(R.id.connectionGroupValue)).setText(
                getString(R.string.connection_group_value, AndroidWorkerDemoApplication.WORKER_GROUP_ID));

        // Drafts, especially the secret, must not enter Activity/view saved state or autofill.
        for (EditText input : new EditText[]{runtimeInput, keyIdInput, secretInput}) {
            input.setSaveEnabled(false);
            input.setFreezesText(false);
            if (Build.VERSION.SDK_INT >= 26) {
                input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
                input.setImeOptions(input.getImeOptions() | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            }
        }
        if (Build.VERSION.SDK_INT >= 26) {
            findViewById(R.id.connectionSettingsContent).setImportantForAutofill(
                    View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }

        findViewById(R.id.saveConnectionSettingsButton).setOnClickListener(ignored -> save());
        findViewById(R.id.restoreConnectionDefaultsButton).setOnClickListener(ignored -> restoreDefaults());
        findViewById(R.id.openApplicationInfoButton).setOnClickListener(ignored -> openApplicationInfo());
        populateEditor();
        renderSummaries();
    }

    @Override protected void onResume() {
        super.onResume();
        renderSummaries();
    }

    @Override protected void onPause() {
        clearSecret();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        clearSecret();
        super.onSaveInstanceState(outState);
    }

    private void populateEditor() {
        AndroidConnectionSettings settings = application.savedConnectionSettings();
        if (settings == null) settings = application.defaultConnectionSettings();
        runtimeInput.setText(settings.runtimeApiBaseUrl().toString());
        keyIdInput.setText(settings.keyId() == null ? "" : settings.keyId());
        clearSecret();
    }

    private void renderSummaries() {
        AndroidConnectionSettings active = application.activeConnectionSettings();
        effectiveValue.setText(active == null
                ? getString(R.string.connection_effective_unavailable)
                : summary(active));
        String savedError = application.savedConnectionError();
        if (savedError != null) {
            savedValue.setText(getString(R.string.connection_saved_unavailable, savedError));
        } else {
            AndroidConnectionSettings saved = application.savedConnectionSettings();
            savedValue.setText(saved == null
                    ? getString(R.string.connection_saved_default, summary(application.defaultConnectionSettings()))
                    : summary(saved));
        }
        pendingValue.setText(savedError != null ? R.string.connection_pending_unavailable
                : application.connectionRestartRequired()
                ? R.string.connection_restart_pending : R.string.connection_no_pending_change);
        String error = application.connectionStartupError();
        startupError.setText(error == null ? "" : getString(R.string.connection_startup_error, error));
        startupError.setVisibility(error == null ? View.GONE : View.VISIBLE);
    }

    private String summary(AndroidConnectionSettings settings) {
        String access = settings.hasGatewayCredential()
                ? getString(R.string.connection_credential_summary, settings.keyId())
                : getString(R.string.connection_anonymous_summary);
        return getString(R.string.connection_summary, settings.runtimeApiBaseUrl().toString(), access);
    }

    private void save() {
        feedback.setText("");
        runtimeInput.setError(null);
        keyIdInput.setError(null);
        secretInput.setError(null);
        if (runtimeInput.getText().toString().trim().isEmpty()) {
            runtimeInput.setError(getString(R.string.connection_runtime_required));
            return;
        }
        if (keyIdInput.getText().toString().isEmpty()) {
            keyIdInput.setError(getString(R.string.connection_key_id_required));
            return;
        }
        if (secretInput.getText().toString().isBlank()) {
            secretInput.setError(getString(R.string.connection_secret_required));
            return;
        }
        AndroidConnectionSettings settings;
        try {
            settings = AndroidConnectionSettings.authenticated(
                    runtimeInput.getText().toString(),
                    keyIdInput.getText().toString(),
                    secretInput.getText().toString());
        } catch (RuntimeException invalid) {
            // URI/parser exceptions can contain input. Only fixed user guidance is rendered.
            showFeedback(R.string.connection_invalid_input, true);
            return;
        }
        try {
            application.saveConnectionSettings(settings);
            populateEditor();
            renderSummaries();
            showFeedback(R.string.connection_saved_notice, false);
        } catch (RuntimeException unavailable) {
            renderSummaries();
            showFeedback(R.string.connection_save_failed, true);
        }
    }

    private void restoreDefaults() {
        clearSecret();
        try {
            application.restoreDefaultConnectionSettings();
            AndroidConnectionSettings defaults = application.defaultConnectionSettings();
            runtimeInput.setText(defaults.runtimeApiBaseUrl().toString());
            keyIdInput.setText("");
            renderSummaries();
            showFeedback(R.string.connection_restored_notice, false);
        } catch (RuntimeException unavailable) {
            renderSummaries();
            showFeedback(R.string.connection_restore_failed, true);
        }
    }

    private void openApplicationInfo() {
        clearSecret();
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", getPackageName(), null)));
        } catch (ActivityNotFoundException unavailable) {
            showFeedback(R.string.connection_application_info_unavailable, true);
        }
    }

    private void clearSecret() {
        if (secretInput != null) secretInput.getText().clear();
    }

    private void showFeedback(int message, boolean error) {
        feedback.setTextColor(error ? 0xffb3261e : 0xff006c4c);
        feedback.setText(message);
    }
}
