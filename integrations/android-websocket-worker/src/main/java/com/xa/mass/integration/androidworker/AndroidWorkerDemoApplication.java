package com.xa.mass.integration.androidworker;

import android.app.Application;
import com.xa.mass.worker.android.AndroidWorker;
import com.xa.mass.worker.android.AndroidWorkerHostResources;
import java.net.URI;
import java.time.Duration;

public final class AndroidWorkerDemoApplication extends Application {

    private static final String WORKER_GROUP_ID = "android-demo-workers";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private AndroidWorkerDemoHost workerHost;
    private AndroidWorkerHostResources workerResources;

    @Override
    public void onCreate() {
        super.onCreate();
        AndroidDeviceProperties deviceProperties =
                new AndroidDeviceProperties(this);
        AndroidDemoStateCapability demoCapability =
                new AndroidDemoStateCapability(this, deviceProperties);
        URI runtimeApiBaseUrl = URI.create(
                getString(R.string.runtime_api_base_url)
        );
        AndroidWorkerHostResources resources =
                AndroidWorkerHostResources.create(
                        1,
                        4,
                        "xa-android-worker-demo"
                );
        AndroidWorker worker;
        try {
            worker = AndroidWorker.builder(
                            this,
                            runtimeApiBaseUrl,
                            WORKER_GROUP_ID
                    )
                    .workerProperties(deviceProperties)
                    .eventDefinitions(demoCapability.definitions())
                    .hostResources(resources)
                    .requestTimeout(REQUEST_TIMEOUT)
                    .build();
        } catch (RuntimeException | Error error) {
            resources.close();
            throw error;
        }
        AndroidWorkerDemoHost host = null;
        try {
            host = new AndroidWorkerDemoHost(
                    worker,
                    demoCapability,
                    resources.controlExecutor()
            );
            host.start();
            workerResources = resources;
            workerHost = host;
        } catch (RuntimeException | Error error) {
            try {
                if (host == null) {
                    worker.close();
                } else {
                    host.close();
                }
            } finally {
                resources.close();
            }
            throw error;
        }
    }

    AndroidWorkerDemoHost workerHost() {
        if (workerHost == null) {
            throw new IllegalStateException(
                    "Android Worker demo host is not initialized"
            );
        }
        return workerHost;
    }

    @Override
    public void onTerminate() {
        try {
            if (workerHost != null) {
                workerHost.close();
            }
        } finally {
            if (workerResources != null) {
                workerResources.close();
            }
        }
        super.onTerminate();
    }
}
