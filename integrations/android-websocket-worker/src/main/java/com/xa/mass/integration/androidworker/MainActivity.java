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
import java.net.URI;

public final class MainActivity extends Activity {

    private AndroidWorkerDemoController controller;
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
        controller = new AndroidWorkerDemoController(
                getApplicationContext(),
                URI.create(getString(R.string.runtime_api_base_url)),
                this::render
        );
        connectButton.setOnClickListener(view -> controller.start());
        disconnectButton.setOnClickListener(view -> controller.stop());
        findViewById(R.id.incrementButton).setOnClickListener(
                view -> controller.incrementCounter()
        );
        findViewById(R.id.resetButton).setOnClickListener(
                view -> controller.resetCounter()
        );
        findViewById(R.id.copyWorkerIdButton).setOnClickListener(
                view -> copyWorkerId()
        );
        render(controller.snapshot());
    }

    @Override
    protected void onStart() {
        super.onStart();
        controller.start();
    }

    @Override
    protected void onStop() {
        controller.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        controller.close();
        super.onDestroy();
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

    private void render(AndroidWorkerDemoController.Snapshot snapshot) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        statusValue.setText(snapshot.state().name());
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
        boolean stopped = snapshot.state()
                == AndroidWorkerDemoController.State.STOPPED
                || snapshot.state()
                == AndroidWorkerDemoController.State.ERROR;
        connectButton.setEnabled(stopped);
        disconnectButton.setEnabled(!stopped);
    }

    private void copyWorkerId() {
        String workerId = controller.snapshot().workerId();
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
