package com.xa.mass.android.workerdemo;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.xa.mass.android.capabilities.AndroidDemoCapabilities;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import java.text.DateFormat;
import java.util.Date;

public final class MainActivity extends Activity {

    private final Runnable workerListener = this::requestRender;
    private final AndroidDemoCapabilities.Listener capabilityListener =
            this::requestRender;

    private AndroidWorkerHostControl worker;
    private AndroidDemoCapabilities demoCapabilities;
    private AndroidWorkerDemoApplication application;
    private TextView statusValue;
    private TextView workerIdValue;
    private TextView endpointValue;
    private TextView capabilityHttpValue;
    private TextView counterValue;
    private TextView processedValue;
    private TextView lastEventValue;
    private TextView errorValue;
    private TextView connectionControlValue;
    private Button connectButton;
    private Button disconnectButton;
    private View copyWorkerIdButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        application =
                (AndroidWorkerDemoApplication) getApplication();
        worker = application.hasWorker() ? application.workerControl() : null;
        demoCapabilities = application.demoCapabilities();

        connectButton.setOnClickListener(view -> { if (worker != null) worker.start(); });
        disconnectButton.setOnClickListener(view -> { if (worker != null) worker.stop(); });
        findViewById(R.id.messagesButton).setOnClickListener(
                view -> startActivity(new Intent(this, MessagesActivity.class)));
        findViewById(R.id.incrementButton).setOnClickListener(
                view -> demoCapabilities.incrementCounter()
        );
        findViewById(R.id.resetButton).setOnClickListener(
                view -> demoCapabilities.resetCounter()
        );
        findViewById(R.id.copyWorkerIdButton).setOnClickListener(
                view -> copyWorkerId()
        );
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (worker != null) {
            worker.addListener(workerListener);
            worker.onForeground();
        }
        demoCapabilities.addListener(capabilityListener);
        render();
    }

    @Override
    protected void onStop() {
        demoCapabilities.removeListener(capabilityListener);
        if (worker != null) worker.removeListener(workerListener);
        super.onStop();
    }

    private void bindViews() {
        statusValue = findViewById(R.id.statusValue);
        workerIdValue = findViewById(R.id.workerIdValue);
        endpointValue = findViewById(R.id.endpointValue);
        capabilityHttpValue = findViewById(R.id.capabilityHttpValue);
        counterValue = findViewById(R.id.counterValue);
        processedValue = findViewById(R.id.processedValue);
        lastEventValue = findViewById(R.id.lastEventValue);
        errorValue = findViewById(R.id.errorValue);
        connectionControlValue = findViewById(R.id.connectionControlValue);
        connectButton = findViewById(R.id.connectButton);
        disconnectButton = findViewById(R.id.disconnectButton);
        copyWorkerIdButton = findViewById(R.id.copyWorkerIdButton);
    }

    private void requestRender() {
        runOnUiThread(this::render);
    }

    private void render() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        WorkerLifecycle.Snapshot workerSnapshot = worker == null ? null : worker.snapshot();
        AndroidDemoCapabilities.Snapshot demoSnapshot =
                demoCapabilities.snapshot();
        statusValue.setText(workerSnapshot == null ? "STARTUP UNAVAILABLE" : workerSnapshot.state().name());
        workerIdValue.setText(orFallback(
                workerSnapshot == null ? null : workerSnapshot.workerId(),
                "Not registered"
        ));
        endpointValue.setText(workerSnapshot == null || workerSnapshot.endpointUri() == null
                ? "Not bound"
                : workerSnapshot.endpointUri().toString());
        capabilityHttpValue.setText(capabilityHttpStatus());
        counterValue.setText("Counter: " + demoSnapshot.counter());
        processedValue.setText(String.valueOf(
                demoSnapshot.processedCommands()
        ));
        lastEventValue.setText(orFallback(
                demoSnapshot.lastEvent(),
                "None"
        ));
        String diagnostic = workerSnapshot == null
                ? application.connectionStartupError() : workerSnapshot.diagnosticMessage();
        errorValue.setText(orFallback(diagnostic, ""));
        errorValue.setVisibility(diagnostic == null ? View.GONE : View.VISIBLE);
        connectionControlValue.setText(connectionControlStatus(workerSnapshot));
        boolean restartable = workerSnapshot != null && workerSnapshot.state()
                == WorkerLifecycle.State.STOPPED;
        connectButton.setEnabled(restartable);
        disconnectButton.setEnabled(workerSnapshot != null
                && (worker.status().desiredRunning() || !restartable));
        copyWorkerIdButton.setEnabled(workerSnapshot != null && workerSnapshot.workerId() != null);
    }

    private String connectionControlStatus(WorkerLifecycle.Snapshot snapshot) {
        if (snapshot == null) return "";
        StringBuilder text = new StringBuilder();
        if (snapshot.stopReason() != null) {
            text.append("停止原因：").append(snapshot.stopReason().name());
        }
        if (snapshot.cooldownUntilMillis() != null) {
            text.append("\nServer 冷却至：").append(formatTime(snapshot.cooldownUntilMillis()));
        }
        Long nextCheck = worker.status().nextCheckAtMillis();
        if (nextCheck != null) {
            text.append("\n预计下次接入检查：").append(formatTime(nextCheck));
            text.append("\n仅在 App 进程可运行时检查；不会唤醒设备。可手动尝试连接或停止检查。");
        }
        return text.toString();
    }

    private static String formatTime(long millis) {
        return DateFormat.getDateTimeInstance().format(new Date(millis));
    }

    private String capabilityHttpStatus() {
        if (application.capabilityHttpRunning()) {
            return application.capabilityHttpEndpoint().toString();
        }
        return "Unavailable: " + orFallback(
                application.capabilityHttpDiagnostic(),
                "startup failed"
        );
    }

    private void copyWorkerId() {
        String workerId = worker == null ? null : worker.snapshot().workerId();
        if (workerId == null) {
            Toast.makeText(
                    this,
                    "Worker ID is not available",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(
                Context.CLIPBOARD_SERVICE
        );
        clipboard.setPrimaryClip(
                ClipData.newPlainText("XA Mass Worker ID", workerId)
        );
        Toast.makeText(
                this,
                "Worker ID copied",
                Toast.LENGTH_SHORT
        ).show();
    }

    private static String orFallback(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}
