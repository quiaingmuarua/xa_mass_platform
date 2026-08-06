package com.xa.mass.integration.androidworker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import android.app.Application;
import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class AndroidWorkerIdentityStoreTest {

    private static final String WORKER_ID =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";

    private Application application;

    @Before
    public void clearState() {
        application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences(
                "android-worker-demo",
                Context.MODE_PRIVATE
        ).edit().clear().commit();
    }

    @Test
    public void persistsLongLivedIdentityAndCounter() {
        AndroidWorkerIdentityStore store =
                new AndroidWorkerIdentityStore(
                        application,
                        AndroidWorkerDemoController.WORKER_GROUP_ID
                );

        AndroidWorkerIdentityStore.Identity created =
                store.loadOrCreateIdentity();
        assertEquals(
                AndroidWorkerDemoController.WORKER_GROUP_ID,
                created.workerGroupId()
        );
        assertEquals(true, created.clientWorkerKey().startsWith(
                "android-demo-"
        ));
        assertNull(created.workerId());

        store.persistWorkerId(WORKER_ID);
        assertEquals(1, store.incrementCounter());
        assertEquals(0, store.resetCounter());

        AndroidWorkerIdentityStore.Identity restored =
                new AndroidWorkerIdentityStore(
                        application,
                        AndroidWorkerDemoController.WORKER_GROUP_ID
                ).loadOrCreateIdentity();
        assertEquals(created.clientWorkerKey(), restored.clientWorkerKey());
        assertEquals(WORKER_ID, restored.workerId());
        assertEquals(0, store.counter());
    }

    @Test
    public void refusesToReplaceOrMisrouteStoredIdentity() {
        AndroidWorkerIdentityStore store =
                new AndroidWorkerIdentityStore(
                        application,
                        AndroidWorkerDemoController.WORKER_GROUP_ID
                );
        store.loadOrCreateIdentity();
        store.persistWorkerId(WORKER_ID);

        assertThrows(
                IllegalStateException.class,
                () -> store.persistWorkerId(
                        "4a2f9bc3-c146-4dce-ae85-6f44e94b5cb3"
                )
        );
        assertThrows(
                IllegalStateException.class,
                () -> new AndroidWorkerIdentityStore(
                        application,
                        "different-group"
                ).loadOrCreateIdentity()
        );
    }
}
