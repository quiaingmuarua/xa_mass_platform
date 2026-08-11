package com.xa.mass.worker.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import android.app.Application;
import android.content.Context;
import java.util.UUID;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class AndroidWorkerIdentityStoreTest {

    private static final String WORKER_ID =
            "server-issued-worker-id";

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
    public void generatesAndRetainsCanonicalClientWorkerKey() {
        AndroidClientWorkerKeyStore first =
                new AndroidClientWorkerKeyStore(application, "group-1");
        String generated = first.loadOrCreate();

        assertEquals(UUID.fromString(generated).toString(), generated);
        assertEquals(
                generated,
                new AndroidClientWorkerKeyStore(
                        application,
                        "group-1"
                ).loadOrCreate()
        );
    }

    @Test
    public void workerIdStorePersistsAndRefusesReplacement() throws Exception {
        new AndroidClientWorkerKeyStore(
                application,
                "group-1"
        ).loadOrCreate();
        AndroidWorkerIdentityStore store = new AndroidWorkerIdentityStore(
                application,
                "group-1"
        );

        assertFalse(store.loadWorkerId().isPresent());
        assertThrows(
                IllegalStateException.class,
                () -> store.saveWorkerId(" ")
        );
        store.saveWorkerId(WORKER_ID);
        store.saveWorkerId(WORKER_ID);
        assertEquals(WORKER_ID, store.loadWorkerId().orElseThrow());

        assertThrows(
                IllegalStateException.class,
                () -> store.saveWorkerId("different-worker-id")
        );
    }

    @Test
    public void coordinatesAreIsolatedByWorkerGroup() throws Exception {
        String firstKey = new AndroidClientWorkerKeyStore(
                application,
                "group-1"
        ).loadOrCreate();
        String secondKey = new AndroidClientWorkerKeyStore(
                application,
                "group-2"
        ).loadOrCreate();

        assertFalse(firstKey.equals(secondKey));
        new AndroidWorkerIdentityStore(
                application,
                "group-1"
        ).saveWorkerId(WORKER_ID);
        assertFalse(new AndroidWorkerIdentityStore(
                application,
                "group-2"
        ).loadWorkerId().isPresent());
    }

    @Test
    public void incompletePersistedIdentityFailsClosed() {
        String prefix = AndroidWorkerIdentityStore.keyPrefix("group-1")
                + ".identity.";
        application.getSharedPreferences(
                AndroidWorkerIdentityStore.PREFERENCES,
                Context.MODE_PRIVATE
        ).edit().putString(
                prefix + "workerId",
                WORKER_ID
        ).commit();

        assertThrows(
                IllegalStateException.class,
                () -> new AndroidClientWorkerKeyStore(
                        application,
                        "group-1"
                ).loadOrCreate()
        );
        assertThrows(
                IllegalStateException.class,
                () -> new AndroidWorkerIdentityStore(
                        application,
                        "group-1"
                ).loadWorkerId()
        );
    }
}
