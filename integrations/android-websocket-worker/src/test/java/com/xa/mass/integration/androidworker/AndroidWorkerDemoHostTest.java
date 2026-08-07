package com.xa.mass.integration.androidworker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.xa.mass.worker.android.AndroidWorker;
import com.xa.mass.worker.runtime.WorkerLifecycle;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class AndroidWorkerDemoHostTest {

    private Application application;
    private AndroidWorkerDemoHost host;

    @Before
    public void setUp() {
        application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences(
                "xa-mass-android-worker",
                Context.MODE_PRIVATE
        ).edit().clear().commit();
        application.getSharedPreferences(
                AndroidDemoStateCapability.PREFERENCES,
                Context.MODE_PRIVATE
        ).edit().clear().commit();

        AndroidDeviceProperties deviceProperties =
                new AndroidDeviceProperties(application);
        AndroidDemoStateCapability capability =
                new AndroidDemoStateCapability(
                        application,
                        deviceProperties
                );
        AndroidWorker worker = AndroidWorker.builder(
                        application,
                        URI.create("http://127.0.0.1:18082"),
                        "android-demo-workers"
                )
                .workerProperties(ignored -> Map.of(
                        "runtime",
                        "android"
                ))
                .eventDefinitions(capability.definitions())
                .build();
        host = new AndroidWorkerDemoHost(
                worker,
                capability,
                new Handler(Looper.getMainLooper())
        );
    }

    @After
    public void tearDown() {
        host.close();
    }

    @Test
    public void mergesWorkerAndCapabilityWithoutOwningTheirMechanisms() {
        AtomicReference<AndroidWorkerDemoHost.Snapshot> observed =
                new AtomicReference<>();
        host.addListener(observed::set);

        assertEquals(
                WorkerLifecycle.State.STOPPED,
                host.snapshot().state()
        );
        assertEquals(1, host.incrementCounter());
        ShadowLooper.idleMainLooper();

        assertEquals(1, host.snapshot().counter());
        assertNotNull(observed.get());
        assertEquals(1, observed.get().counter());
        assertEquals(0, host.resetCounter());
        assertEquals(0, host.snapshot().counter());
    }
}
