package com.xa.mass.android.capabilities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class AndroidDemoCapabilitiesTest {

    private Application application;

    @Before
    public void clearState() {
        application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences(
                AndroidDemoCapabilities.PREFERENCES,
                Context.MODE_PRIVATE
        ).edit().clear().commit();
    }

    @Test
    public void exposesExactlyTheStateAndBatteryDefinitions() {
        AndroidDemoCapabilities capabilities = capabilities(
                AndroidDemoCapabilities.BatteryReading.available(82, false)
        );
        Map<String, WorkerEventDefinition<?>> definitions = definitions(
                capabilities
        );

        assertEquals(2, definitions.size());
        assertTrue(definitions.containsKey(
                AndroidDemoCapabilities.STATE_READ
        ));
        assertTrue(definitions.containsKey(
                AndroidDemoCapabilities.BATTERY_READ
        ));
        assertThrows(
                UnsupportedOperationException.class,
                () -> capabilities.definitions().clear()
        );
    }

    @Test
    public void stateReadPreservesCounterAndDeviceFields() throws Exception {
        AndroidDemoCapabilities capabilities = capabilities(
                AndroidDemoCapabilities.BatteryReading.available(82, false)
        );
        capabilities.incrementCounter();

        Map<String, Object> result = execute(
                capabilities,
                AndroidDemoCapabilities.STATE_READ
        );

        assertEquals(1L, result.get("counter"));
        assertEquals(application.getPackageName(), result.get("packageName"));
        assertFalse(result.containsKey("workerId"));
        assertEquals(1, capabilities.snapshot().processedCommands());
        assertEquals(
                AndroidDemoCapabilities.STATE_READ,
                capabilities.snapshot().lastEvent()
        );
    }

    @Test
    public void batteryReadReturnsTheCurrentOneShotSnapshot()
            throws Exception {
        AndroidDemoCapabilities capabilities = capabilities(
                AndroidDemoCapabilities.BatteryReading.available(82, true)
        );

        Map<String, Object> result = execute(
                capabilities,
                AndroidDemoCapabilities.BATTERY_READ
        );

        assertEquals(Boolean.TRUE, result.get("available"));
        assertEquals(82L, result.get("capacityPercent"));
        assertEquals(Boolean.TRUE, result.get("charging"));
        assertEquals(1, capabilities.snapshot().processedCommands());
        assertEquals(
                AndroidDemoCapabilities.BATTERY_READ,
                capabilities.snapshot().lastEvent()
        );
    }

    @Test
    public void unavailableBatteryProducesSafeNullFields() throws Exception {
        AndroidDemoCapabilities capabilities = capabilities(
                AndroidDemoCapabilities.BatteryReading.unavailable()
        );

        Map<String, Object> result = execute(
                capabilities,
                AndroidDemoCapabilities.BATTERY_READ
        );

        assertEquals(Boolean.FALSE, result.get("available"));
        assertTrue(result.containsKey("capacityPercent"));
        assertNull(result.get("capacityPercent"));
        assertTrue(result.containsKey("charging"));
        assertNull(result.get("charging"));
    }

    @Test
    public void listenersObserveLocalAndRemoteCapabilityChanges()
            throws Exception {
        AndroidDemoCapabilities capabilities = capabilities(
                AndroidDemoCapabilities.BatteryReading.available(50, false)
        );
        AtomicInteger notifications = new AtomicInteger();
        AndroidDemoCapabilities.Listener listener =
                notifications::incrementAndGet;
        capabilities.addListener(listener);

        capabilities.incrementCounter();
        execute(capabilities, AndroidDemoCapabilities.BATTERY_READ);
        capabilities.removeListener(listener);
        capabilities.resetCounter();

        assertEquals(3, notifications.get());
    }

    private AndroidDemoCapabilities capabilities(
            AndroidDemoCapabilities.BatteryReading reading
    ) {
        return new AndroidDemoCapabilities(application, () -> reading);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> execute(
            AndroidDemoCapabilities capabilities,
            String eventCode
    ) throws Exception {
        WorkerEventDefinition<Map<String, Object>> definition =
                (WorkerEventDefinition<Map<String, Object>>)
                        definitions(capabilities).get(eventCode);
        Map<String, Object> parameters =
                definition.parameterResolver().resolve("{}");
        return Jsons.parseObject(
                definition.handler().execute(parameters)
        );
    }

    private static Map<String, WorkerEventDefinition<?>> definitions(
            AndroidDemoCapabilities capabilities
    ) {
        Map<String, WorkerEventDefinition<?>> result = new LinkedHashMap<>();
        for (WorkerEventDefinition<?> definition : capabilities.definitions()) {
            result.put(definition.eventCode(), definition);
        }
        return result;
    }
}
