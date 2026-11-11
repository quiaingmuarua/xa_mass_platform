package com.xa.mass.android.workerdemo;

import android.app.Application;

import com.xa.mass.android.capabilityhttp.AndroidCapabilityHttpServer;
import com.xa.mass.android.capabilities.AndroidDemoCapabilities;
import com.xa.mass.worker.android.AndroidWorker;
import com.xa.mass.worker.execution.WorkerEventDefinition;
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
    private AndroidConnectionSettingsStore connectionSettingsStore;
    private AndroidConnectionSettings activeConnectionSettings;
    private AndroidConnectionSettings savedConnectionSettings;
    private AndroidConnectionSettings defaultConnectionSettings;
    private String connectionStartupError;
    private String savedConnectionError;

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
        // Pure presentation owners also exist when a saved connection cannot be loaded.
        // A previous process's notification can never recover its Reporter.
        messageNotifications = new AndroidMessageNotifications(this, messages);
        messages.addListener(messageNotifications::requestRefresh);
        connectionSettingsStore = createConnectionSettingsStore();
        defaultConnectionSettings = AndroidConnectionSettings.defaults(
                URI.create(getString(R.string.runtime_api_base_url)));
        try {
            savedConnectionSettings = connectionSettingsStore.load();
            activeConnectionSettings = savedConnectionSettings == null
                    ? defaultConnectionSettings : savedConnectionSettings;
        } catch (RuntimeException error) {
            connectionStartupError = "连接配置不可用，请重新保存配置或恢复构建默认，然后完整重启 App。";
            savedConnectionError = connectionStartupError;
            capabilityHttpDiagnostic = "Connection configuration is unavailable";
            return;
        }
        try {
            worker = AndroidWorker.create(
                    this,
                    activeConnectionSettings.runtimeApiBaseUrl(),
                    WORKER_GROUP_ID,
                    deviceProperties,
                    workerDefinitions,
                    activeConnectionSettings.connectionOptions()
            );
        } catch (RuntimeException unavailable) {
            // The concrete client can reject a URI that passed the shared credential checks.
            // Worker.create owns cleanup of partially constructed SDK resources.
            activeConnectionSettings = null;
            connectionStartupError = "无法初始化连接，请修复配置并完整重启 App。";
            savedConnectionError = connectionStartupError;
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

    AndroidConnectionSettingsStore createConnectionSettingsStore() {
        return new AndroidConnectionSettingsStore(this);
    }

    AndroidConnectionSettings activeConnectionSettings() { return activeConnectionSettings; }
    AndroidConnectionSettings savedConnectionSettings() { return savedConnectionSettings; }
    AndroidConnectionSettings defaultConnectionSettings() { return defaultConnectionSettings; }
    String connectionStartupError() { return connectionStartupError; }
    String savedConnectionError() { return savedConnectionError; }
    boolean hasWorker() { return worker != null; }

    void saveConnectionSettings(AndroidConnectionSettings settings) {
        try {
            connectionSettingsStore.save(settings);
        } catch (RuntimeException unavailable) {
            savedConnectionError = "保存结果未确认。当前连接未改变，请显式重新保存或完整重启后核对。";
            throw unavailable;
        }
        savedConnectionSettings = settings;
        savedConnectionError = null;
    }

    void restoreDefaultConnectionSettings() {
        try {
            connectionSettingsStore.clear();
        } catch (RuntimeException unavailable) {
            savedConnectionError = "恢复默认的结果未确认。当前连接未改变，请完整重启后核对。";
            throw unavailable;
        }
        savedConnectionSettings = null;
        savedConnectionError = null;
    }

    boolean connectionRestartRequired() {
        AndroidConnectionSettings next = savedConnectionSettings == null
                ? defaultConnectionSettings : savedConnectionSettings;
        return activeConnectionSettings == null || !activeConnectionSettings.sameConnection(next);
    }

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
