package com.xa.mass.android.workerdemo;

import android.app.Application;

import com.xa.mass.android.capabilityhttp.AndroidCapabilityHttpServer;
import com.xa.mass.android.capabilities.AndroidDemoCapabilities;
import com.xa.mass.worker.android.AndroidWorker;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.runtime.WorkerConnectionOptions;
import com.xa.mass.worker.runtime.WorkerLifecycle;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AndroidWorkerDemoApplication extends Application {

    static final String WORKER_GROUP_ID = "android-demo-workers";

    private AndroidWorker worker;
    private AndroidDemoCapabilities demoCapabilities;
    private AndroidWorkerLabEvents labEvents;
    private AndroidMessageDemo messages;
    private AndroidMessageNotifications messageNotifications;
    private AndroidCapabilityHttpServer capabilityHttpServer;
    private String capabilityHttpDiagnostic;
    private String connectionStartupError;

    @Override
    public void onCreate() {
        super.onCreate();
        AndroidDeviceProperties deviceProperties =
                new AndroidDeviceProperties(this);
        demoCapabilities = new AndroidDemoCapabilities(this);
        labEvents = new AndroidWorkerLabEvents();
        List<WorkerEventDefinition<?>> workerDefinitions = new ArrayList<>();
        workerDefinitions.addAll(demoCapabilities.definitions());
        workerDefinitions.addAll(labEvents.definitions());
        // Sending is a Worker capability only. HTTP retains the existing definitions and read-only message evidence.
        List<WorkerEventDefinition<?>> hostDefinitions = new ArrayList<>(workerDefinitions);
        messages = new AndroidMessageDemo(() -> worker == null ? null : worker.snapshot(), deviceProperties.demoPhone());
        workerDefinitions.add(messages.sendingDefinition());
        hostDefinitions.add(messages.observationDefinition());
        workerDefinitions = Collections.unmodifiableList(workerDefinitions);
        // Pure presentation owners also exist when required access material cannot be loaded.
        // A previous process's notification can never recover its Reporter.
        messageNotifications = new AndroidMessageNotifications(this, messages);
        messages.addListener(messageNotifications::requestRefresh);
        URI runtimeApiBaseUrl;
        WorkerConnectionOptions connectionOptions;
        try {
            runtimeApiBaseUrl = URI.create(getString(R.string.runtime_api_base_url));
            connectionOptions = loadWorkerConnectionOptions(runtimeApiBaseUrl);
        } catch (RuntimeException error) {
            connectionStartupError = "Worker 接入不可用，请检查构建地址和私有 worker-access.json，然后完整重启 App。";
            capabilityHttpDiagnostic = "Worker access is unavailable";
            return;
        }
        try {
            worker = AndroidWorker.create(
                    this,
                    runtimeApiBaseUrl,
                    WORKER_GROUP_ID,
                    deviceProperties,
                    workerDefinitions,
                    connectionOptions
            );
        } catch (RuntimeException unavailable) {
            // The concrete client can reject a URI that passed the shared credential checks.
            // Worker.create owns cleanup of partially constructed SDK resources.
            connectionStartupError = "无法初始化 Worker 连接，请检查构建地址并完整重启 App。";
            capabilityHttpDiagnostic = "Connection initialization is unavailable";
            return;
        }
        worker.addListener(snapshot -> {
            if (snapshot.state() == WorkerLifecycle.State.STOPPED
                    && worker.snapshot().state() == WorkerLifecycle.State.STOPPED) messages.revokeReporters();
        });
        capabilityHttpServer = AndroidCapabilityHttpServer.create(
                getResources().getInteger(R.integer.capability_http_port),
                AndroidWorkerHostEvents.assemble(
                        hostDefinitions,
                        worker,
                        demoCapabilities,
                        labEvents
                )
        );
        try {
            capabilityHttpServer.start();
        } catch (IOException error) {
            capabilityHttpDiagnostic = diagnosticMessage(error);
            capabilityHttpServer.close();
        }
        worker.start();
    }

    WorkerConnectionOptions loadWorkerConnectionOptions(URI runtimeApiBaseUrl) {
        return AndroidWorkerAccessFile.load(this, runtimeApiBaseUrl,
                getResources().getBoolean(R.bool.gateway_credential_required));
    }

    String connectionStartupError() { return connectionStartupError; }
    boolean hasWorker() { return worker != null; }

    AndroidWorker worker() {
        if (worker == null) {
            throw new IllegalStateException(
                    "Android Worker is not initialized"
            );
        }
        return worker;
    }

    AndroidDemoCapabilities demoCapabilities() {
        if (demoCapabilities == null) {
            throw new IllegalStateException(
                    "Android demo capabilities are not initialized"
            );
        }
        return demoCapabilities;
    }

    AndroidMessageDemo messages() { return messages; }
    AndroidMessageNotifications messageNotifications() { return messageNotifications; }

    boolean capabilityHttpRunning() {
        return capabilityHttpServer != null
                && capabilityHttpServer.isRunning();
    }

    URI capabilityHttpEndpoint() {
        return capabilityHttpServer == null
                ? null
                : capabilityHttpServer.endpoint();
    }

    String capabilityHttpDiagnostic() {
        return capabilityHttpDiagnostic;
    }

    @Override
    public void onTerminate() {
        if (capabilityHttpServer != null) {
            capabilityHttpServer.close();
        }
        if (worker != null) {
            worker.close();
        }
        super.onTerminate();
    }

    private static String diagnosticMessage(IOException error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }
}
