package com.xa.mass.integration.androidworker;

import android.app.Application;
import com.xa.mass.worker.android.AndroidWorker;
import java.net.URI;
import java.time.Duration;

public final class AndroidWorkerDemoApplication extends Application {

    private static final String WORKER_GROUP_ID = "android-demo-workers";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private AndroidWorkerDemoHost workerHost;

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
        AndroidWorkerDemoResources resources =
                new AndroidWorkerDemoResources();
        AndroidWorker worker;
        try {
            worker = AndroidWorker.builder(
                            this,
                            runtimeApiBaseUrl,
                            WORKER_GROUP_ID
                    )
                    .workerProperties(deviceProperties)
                    .eventDefinitions(demoCapability.definitions())
                    .handlerExecutor(resources.handlerExecutor())
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
                    resources
            );
            host.start();
            workerHost = host;
        } catch (RuntimeException | Error error) {
            if (host == null) {
                worker.close();
                resources.close();
            } else {
                host.close();
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
        if (workerHost != null) {
            workerHost.close();
        }
        super.onTerminate();
    }
}
