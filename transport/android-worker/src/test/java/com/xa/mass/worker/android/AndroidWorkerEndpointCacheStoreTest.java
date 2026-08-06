package com.xa.mass.worker.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import java.net.URI;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class AndroidWorkerEndpointCacheStoreTest {

    private static final String WORKER_ID =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";
    private static final String SHA256 =
            "0123456789abcdef0123456789abcdef"
                    + "0123456789abcdef0123456789abcdef";

    private Application application;

    @Before
    public void clearState() {
        application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences(
                AndroidWorkerIdentityStore.PREFERENCES,
                Context.MODE_PRIVATE
        ).edit().clear().commit();
    }

    @Test
    public void storesAndClearsEndpointCoordinates() {
        AndroidWorkerEndpointCacheStore store =
                new AndroidWorkerEndpointCacheStore(
                        application,
                        "android-demo-workers"
                );
        URI endpoint = URI.create("ws://127.0.0.1:18085/socket");

        store.store(
                "android-demo-workers",
                WORKER_ID,
                endpoint,
                SHA256
        );

        AndroidWorkerEndpointCacheStore.Entry cached =
                store.load().orElseThrow();
        assertEquals("android-demo-workers", cached.workerGroupId());
        assertEquals(WORKER_ID, cached.workerId());
        assertEquals(endpoint, cached.endpointUri());
        assertEquals(SHA256, cached.propertiesSha256());

        store.clear();
        assertFalse(store.load().isPresent());
    }

    @Test
    public void corruptCacheIsDiscardedWithoutTouchingIdentity() {
        AndroidWorkerIdentityStore identityStore =
                new AndroidWorkerIdentityStore(
                        application,
                        "android-demo-workers"
                );
        identityStore.loadOrCreateIdentity("installation-1");
        identityStore.persistWorkerId(WORKER_ID);
        String endpointPrefix = AndroidWorkerIdentityStore.keyPrefix(
                "android-demo-workers"
        ) + ".endpoint.";
        assertTrue(application.getSharedPreferences(
                AndroidWorkerIdentityStore.PREFERENCES,
                Context.MODE_PRIVATE
        ).edit()
                .putString(
                        endpointPrefix + "workerGroupId",
                        "android-demo-workers"
                )
                .putString(endpointPrefix + "workerId", WORKER_ID)
                .putString(endpointPrefix + "endpointUri", "not-a-uri")
                .commit());

        AndroidWorkerEndpointCacheStore store =
                new AndroidWorkerEndpointCacheStore(
                        application,
                        "android-demo-workers"
                );

        assertFalse(store.load().isPresent());
        assertEquals(
                WORKER_ID,
                new AndroidWorkerIdentityStore(
                        application,
                        "android-demo-workers"
                ).loadOrCreateIdentity(null).workerId()
        );
    }
}
