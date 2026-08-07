package com.xa.mass.integration.androidworker;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.xa.mass.worker.runtime.WorkerLifecycle;

public final class MainActivity extends Activity {

    private final AndroidWorkerDemoHost.Listener hostListener = this::render;

    private AndroidWorkerDemoHost workerHost;
    private TextView statusValue;
    private TextView workerIdValue;
    private TextView endpointValue;
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
        workerHost = ((AndroidWorkerDemoApplication) getApplication())
                .workerHost();

        connectButton.setOnClickListener(view -> workerHost.start());
        disconnectButton.setOnClickListener(view -> workerHost.stop());
        findViewById(R.id.incrementButton).setOnClickListener(
                view -> workerHost.incrementCounter()
        );
        findViewById(R.id.resetButton).setOnClickListener(
                view -> workerHost.resetCounter()
        );
        findViewById(R.id.copyWorkerIdButton).setOnClickListener(
                view -> copyWorkerId()
        );
        render(workerHost.snapshot());
    }

    @Override
    protected void onStart() {
        super.onStart();
        workerHost.addListener(hostListener);
    }

    @Override
    protected void onStop() {
        workerHost.removeListener(hostListener);
        super.onStop();
    }

    private void bindViews() {
        statusValue = findViewById(R.id.statusValue);
        workerIdValue = findViewById(R.id.workerIdValue);
        endpointValue = findViewById(R.id.endpointValue);
        counterValue = findViewById(R.id.counterValue);
        processedValue = findViewById(R.id.processedValue);
        lastEventValue = findViewById(R.id.lastEventValue);
        errorValue = findViewById(R.id.errorValue);
        connectButton = findViewById(R.id.connectButton);
        disconnectButton = findViewById(R.id.disconnectButton);
    }

    private void render(AndroidWorkerDemoHost.Snapshot snapshot) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        statusValue.setText(
                snapshot.state().name()
                        + " / "
                        + snapshot.connectionState().name()
        );
        workerIdValue.setText(orFallback(
                snapshot.workerId(),
                "Not registered"
        ));
        endpointValue.setText(snapshot.endpointUri() == null
                ? "Not bound"
                : snapshot.endpointUri().toString());
        counterValue.setText("Counter: " + snapshot.counter());
        processedValue.setText(String.valueOf(
                snapshot.processedCommands()
        ));
        lastEventValue.setText(orFallback(
                snapshot.lastEvent(),
                "None"
        ));
        errorValue.setText(orFallback(snapshot.errorMessage(), ""));
        errorValue.setVisibility(snapshot.errorMessage() == null
                ? View.GONE
                : View.VISIBLE);
        boolean restartable = snapshot.state()
                == WorkerLifecycle.State.STOPPED
                || snapshot.state()
                == WorkerLifecycle.State.ERROR;
        connectButton.setEnabled(restartable);
        disconnectButton.setEnabled(!restartable
                && snapshot.state()
                != WorkerLifecycle.State.CLOSED);
    }

    private void copyWorkerId() {
        String workerId = workerHost.snapshot().workerId();
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
