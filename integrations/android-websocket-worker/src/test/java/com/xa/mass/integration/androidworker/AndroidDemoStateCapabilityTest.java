package com.xa.mass.integration.androidworker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.app.Application;
import android.content.Context;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class AndroidDemoStateCapabilityTest {

    private Application application;

    @Before
    public void clearState() {
        application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences(
                AndroidDemoStateCapability.PREFERENCES,
                Context.MODE_PRIVATE
        ).edit().clear().commit();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void handlerReadsDemoAndDeviceStateWithoutWorkerIdentity()
            throws Exception {
        AndroidDeviceProperties deviceProperties =
                new AndroidDeviceProperties(application);
        AndroidDemoStateCapability capability =
                new AndroidDemoStateCapability(
                        application,
                        deviceProperties
                );
        capability.incrementCounter();
        WorkerEventDefinition<Map<String, Object>> definition =
                (WorkerEventDefinition<Map<String, Object>>)
                        capability.definitions().iterator().next();

        Map<String, Object> parameters =
                definition.parameterResolver().resolve("{}");
        Map<String, Object> result = Jsons.parseObject(
                definition.handler().execute(parameters)
        );

        assertEquals(1L, result.get("counter"));
        assertEquals(application.getPackageName(), result.get("packageName"));
        assertFalse(result.containsKey("workerId"));
        assertEquals(1, capability.snapshot().processedCommands());
        assertEquals(
                AndroidDemoStateCapability.EVENT_CODE + " counter=1",
                capability.snapshot().lastEvent()
        );
    }
}
