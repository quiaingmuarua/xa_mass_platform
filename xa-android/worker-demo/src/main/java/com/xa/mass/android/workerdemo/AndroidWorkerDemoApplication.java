package com.xa.mass.android.workerdemo;

import android.app.Application;

import com.xa.mass.android.capabilities.AndroidDemoCapabilities;
import com.xa.mass.worker.android.AndroidWorker;

import java.net.URI;

public final class AndroidWorkerDemoApplication extends Application {

    private static final String WORKER_GROUP_ID = "android-demo-workers";

    private AndroidWorker worker;
    private AndroidDemoCapabilities demoCapabilities;

    @Override
    public void onCreate() {
        super.onCreate();
        URI runtimeApiBaseUrl = URI.create(
                getString(R.string.runtime_api_base_url)
        );
        AndroidDeviceProperties deviceProperties =
                new AndroidDeviceProperties(this);
        demoCapabilities = new AndroidDemoCapabilities(this);
        worker = AndroidWorker.create(
                this,
                runtimeApiBaseUrl,
                WORKER_GROUP_ID,
                deviceProperties,
                demoCapabilities.definitions()
        );
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

    AndroidDemoCapabilities demoCapabilities() {
        if (demoCapabilities == null) {
            throw new IllegalStateException(
                    "Android demo capabilities are not initialized"
            );
        }
        return demoCapabilities;
    }

    @Override
    public void onTerminate() {
        if (worker != null) {
            worker.close();
        }
        super.onTerminate();
    }
}
