package com.xa.mass.integration.androidworker;

import android.app.Application;
import com.xa.mass.transport.android.websocket
        .AndroidOkHttpTextWebSocketClient;
import com.xa.mass.transport.client.okhttp.OkHttpWorkerControlClient;
import java.net.URI;
import java.time.Duration;

public final class AndroidWorkerDemoApplication extends Application {

    private static final String WORKER_GROUP_ID = "android-demo-workers";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RECONNECT_INTERVAL =
            Duration.ofSeconds(1);

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
        AndroidWebSocketWorkerPlugin workerPlugin =
                new AndroidWebSocketWorkerPlugin(
                        WORKER_GROUP_ID,
                        new AndroidWorkerIdentityStore(
                                this,
                                WORKER_GROUP_ID
                        ),
                        new AndroidWorkerEndpointCacheStore(this),
                        deviceProperties::workerProperties,
                        demoCapability.definitions(),
                        () -> new OkHttpWorkerControlClient(
                                runtimeApiBaseUrl
                        ),
                        endpointUri ->
                                new AndroidOkHttpTextWebSocketClient(
                                        endpointUri,
                                        REQUEST_TIMEOUT,
                                        RECONNECT_INTERVAL
                                ),
                        REQUEST_TIMEOUT
                );
        workerHost = new AndroidWorkerDemoHost(
                workerPlugin,
                demoCapability
        );
        workerHost.start();
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
