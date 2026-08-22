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
public class AndroidClientWorkerKeyStoreTest {

    private Application application;

    @Before
    public void clearState() {
        application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences(
                AndroidClientWorkerKeyStore.PREFERENCES,
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
    public void coordinatesAreIsolatedByWorkerGroup() {
        String firstKey = new AndroidClientWorkerKeyStore(
                application,
                "group-1"
        ).loadOrCreate();
        String secondKey = new AndroidClientWorkerKeyStore(
                application,
                "group-2"
        ).loadOrCreate();

        assertFalse(firstKey.equals(secondKey));
    }

    @Test
    public void legacyWorkerIdIsIgnored() {
        String prefix = AndroidClientWorkerKeyStore.keyPrefix("group-1")
                + ".identity.";
        application.getSharedPreferences(
                AndroidClientWorkerKeyStore.PREFERENCES,
                Context.MODE_PRIVATE
        ).edit().putString(
                prefix + "workerId",
                "old-server-worker-id"
        ).commit();

        String generated = new AndroidClientWorkerKeyStore(
                application,
                "group-1"
        ).loadOrCreate();

        assertEquals(UUID.fromString(generated).toString(), generated);
    }

    @Test
    public void incompleteCurrentCoordinateFailsClosed() {
        String prefix = AndroidClientWorkerKeyStore.keyPrefix("group-1")
                + ".identity.";
        application.getSharedPreferences(
                AndroidClientWorkerKeyStore.PREFERENCES,
                Context.MODE_PRIVATE
        ).edit().putString(
                prefix + "workerGroupId",
                "group-1"
        ).commit();

        assertThrows(
                IllegalStateException.class,
                () -> new AndroidClientWorkerKeyStore(
                        application,
                        "group-1"
                ).loadOrCreate()
        );
    }
}
