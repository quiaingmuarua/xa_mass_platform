package com.xa.mass.worker.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class AndroidWorkerIdentityStoreTest {

    private static final String WORKER_GROUP_ID = "android-demo-workers";
    private static final String WORKER_ID =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";

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
    public void persistsLongLivedIdentity() {
        AndroidWorkerIdentityStore store =
                new AndroidWorkerIdentityStore(
                        application,
                        WORKER_GROUP_ID
                );

        AndroidWorkerIdentityStore.Identity created =
                store.loadOrCreateIdentity(null);
        assertEquals(
                WORKER_GROUP_ID,
                created.workerGroupId()
        );
        assertEquals(
                created.clientWorkerKey(),
                java.util.UUID.fromString(
                        created.clientWorkerKey()
                ).toString()
        );
        assertNull(created.workerId());

        store.persistWorkerId(WORKER_ID);

        AndroidWorkerIdentityStore.Identity restored =
                new AndroidWorkerIdentityStore(
                        application,
                        WORKER_GROUP_ID
                ).loadOrCreateIdentity(null);
        assertEquals(created.clientWorkerKey(), restored.clientWorkerKey());
        assertEquals(WORKER_ID, restored.workerId());
    }

    @Test
    public void preservesExistingNonBlankClientWorkerKey() {
        AndroidWorkerIdentityStore store =
                new AndroidWorkerIdentityStore(
                        application,
                        WORKER_GROUP_ID
                );
        AndroidWorkerIdentityStore.Identity identity =
                store.loadOrCreateIdentity("installation-key");

        assertEquals(
                "installation-key",
                identity.clientWorkerKey()
        );
        assertEquals(
                "installation-key",
                store.loadOrCreateIdentity(null).clientWorkerKey()
        );
    }

    @Test
    public void refusesToReplaceOrMisrouteStoredIdentity() {
        AndroidWorkerIdentityStore store =
                new AndroidWorkerIdentityStore(
                        application,
                        WORKER_GROUP_ID
                );
        store.loadOrCreateIdentity("installation-key");
        store.persistWorkerId(WORKER_ID);

        assertThrows(
                IllegalStateException.class,
                () -> store.persistWorkerId(
                        "4a2f9bc3-c146-4dce-ae85-6f44e94b5cb3"
                )
        );
        assertThrows(
                IllegalStateException.class,
                () -> store.loadOrCreateIdentity("different-key")
        );
    }

    @Test
    public void separatesIdentityByWorkerGroup() {
        AndroidWorkerIdentityStore first =
                new AndroidWorkerIdentityStore(
                        application,
                        WORKER_GROUP_ID
                );
        AndroidWorkerIdentityStore second =
                new AndroidWorkerIdentityStore(
                        application,
                        "different-group"
                );

        assertEquals(
                "first-key",
                first.loadOrCreateIdentity("first-key")
                        .clientWorkerKey()
        );
        assertEquals(
                "second-key",
                second.loadOrCreateIdentity("second-key")
                        .clientWorkerKey()
        );
    }
}
