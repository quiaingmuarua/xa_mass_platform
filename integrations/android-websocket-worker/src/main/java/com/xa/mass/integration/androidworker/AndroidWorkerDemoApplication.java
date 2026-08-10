package com.xa.mass.integration.androidworker;

import android.app.Application;

import com.xa.mass.worker.android.AndroidWorker;

import java.net.URI;

public final class AndroidWorkerDemoApplication extends Application {

    private static final String WORKER_GROUP_ID = "android-demo-workers";

    private AndroidWorker worker;
    private AndroidDemoStateCapability demoCapability;

    @Override
    public void onCreate() {
        super.onCreate();
        URI runtimeApiBaseUrl = URI.create(
                getString(R.string.runtime_api_base_url)
        );
        AndroidDeviceProperties deviceProperties =
                new AndroidDeviceProperties(this);
        demoCapability = new AndroidDemoStateCapability(
                this,
                deviceProperties
        );
        worker = AndroidWorker.builder(
                        this,
                        runtimeApiBaseUrl,
                        WORKER_GROUP_ID
                )
                .workerProperties(deviceProperties)
                .eventDefinitions(demoCapability.definitions())
                .build();
        worker.start();
    }

    AndroidWorker worker() {
        if (worker == null) {
            throw new IllegalStateException(
                    "Android Worker is not initialized"
            );
        }
        return worker;
    }

    AndroidDemoStateCapability demoCapability() {
        if (demoCapability == null) {
            throw new IllegalStateException(
                    "Android demo capability is not initialized"
            );
        }
        return demoCapability;
    }

    @Override
    public void onTerminate() {
        if (worker != null) {
            worker.close();
        }
        super.onTerminate();
    }
}
