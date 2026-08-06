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
        store.saveWorkerId(WORKER_ID);
        store.saveWorkerId(WORKER_ID);
        assertEquals(WORKER_ID, store.loadWorkerId().orElseThrow());

        assertThrows(
                IllegalStateException.class,
                () -> store.saveWorkerId(
                        "4a2f9bc3-c146-4dce-ae85-6f44e94b5cb3"
                )
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
