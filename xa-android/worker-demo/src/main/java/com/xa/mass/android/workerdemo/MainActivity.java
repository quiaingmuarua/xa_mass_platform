package com.xa.mass.android.workerdemo;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.xa.mass.android.capabilities.AndroidDemoCapabilities;
import com.xa.mass.worker.runtime.WorkerLifecycle;

public final class MainActivity extends Activity {

    private final WorkerLifecycle.Listener workerListener =
            ignored -> requestRender();
    private final AndroidDemoCapabilities.Listener capabilityListener =
            this::requestRender;

    private WorkerLifecycle worker;
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
    private Button connectButton;
    private Button disconnectButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        application =
                (AndroidWorkerDemoApplication) getApplication();
        worker = application.worker();
        demoCapabilities = application.demoCapabilities();

        connectButton.setOnClickListener(view -> worker.start());
        disconnectButton.setOnClickListener(view -> worker.stop());
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
        worker.addListener(workerListener);
        demoCapabilities.addListener(capabilityListener);
    }

    @Override
    protected void onStop() {
        demoCapabilities.removeListener(capabilityListener);
        worker.removeListener(workerListener);
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
        connectButton = findViewById(R.id.connectButton);
        disconnectButton = findViewById(R.id.disconnectButton);
    }

    private void requestRender() {
        runOnUiThread(this::render);
    }

    private void render() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        WorkerLifecycle.Snapshot workerSnapshot = worker.snapshot();
        AndroidDemoCapabilities.Snapshot demoSnapshot =
                demoCapabilities.snapshot();
        statusValue.setText(workerSnapshot.state().name());
        workerIdValue.setText(orFallback(
                workerSnapshot.workerId(),
                "Not registered"
        ));
        endpointValue.setText(workerSnapshot.endpointUri() == null
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
        errorValue.setText(orFallback(
                workerSnapshot.diagnosticMessage(),
                ""
        ));
        errorValue.setVisibility(
                workerSnapshot.diagnosticMessage() == null
                ? View.GONE
                : View.VISIBLE
        );
        boolean restartable = workerSnapshot.state()
                == WorkerLifecycle.State.STOPPED;
        connectButton.setEnabled(restartable);
        disconnectButton.setEnabled(!restartable);
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
        String workerId = worker.snapshot().workerId();
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
