package com.xa.mass.integration.androidworker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class AndroidWorkerDemoHostTest {

    private Application application;
    private AndroidWorkerDemoHost host;

    @Before
    public void setUp() {
        application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences(
                AndroidWorkerIdentityStore.PREFERENCES,
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
        AndroidWebSocketWorkerPlugin plugin =
                new AndroidWebSocketWorkerPlugin(
                        "android-demo-workers",
                        new AndroidWorkerIdentityStore(
                                application,
                                "android-demo-workers"
                        ),
                        new AndroidWorkerEndpointCacheStore(application),
                        () -> Collections.singletonMap(
                                "runtime",
                                "android"
                        ),
                        capability.definitions(),
                        () -> {
                            throw new AssertionError(
                                    "control must not be used"
                            );
                        },
                        endpoint -> {
                            throw new AssertionError(
                                    "network must not be used"
                            );
                        },
                        Duration.ofSeconds(1)
                );
        host = new AndroidWorkerDemoHost(
                plugin,
                capability,
                new Handler(Looper.getMainLooper())
        );
    }

    @After
    public void tearDown() {
        host.close();
    }

    @Test
    public void mergesPluginAndCapabilityWithoutOwningTheirMechanisms() {
        AtomicReference<AndroidWorkerDemoHost.Snapshot> observed =
                new AtomicReference<>();
        host.addListener(observed::set);

        assertEquals(
                AndroidWebSocketWorkerPlugin.State.STOPPED,
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
