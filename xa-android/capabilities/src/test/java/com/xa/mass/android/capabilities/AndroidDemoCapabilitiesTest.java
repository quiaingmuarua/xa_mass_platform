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
    public void exposesExactlyTheDemoDefinitions() {
        AndroidDemoCapabilities capabilities = capabilities(
                AndroidDemoCapabilities.BatteryReading.available(82, false)
        );
        Map<String, WorkerEventDefinition<?>> definitions = definitions(
                capabilities
        );

        assertEquals(3, definitions.size());
        assertTrue(definitions.containsKey(
                AndroidDemoCapabilities.STATE_READ
        ));
        assertTrue(definitions.containsKey(
                AndroidDemoCapabilities.BATTERY_READ
        ));
        assertTrue(definitions.containsKey(
                AndroidDemoCapabilities.STRING_DIGEST
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
    public void stringDigestRequiresAllowlistedAlgorithmAndUtf8Value()
            throws Exception {
        AndroidDemoCapabilities capabilities = capabilities(
                AndroidDemoCapabilities.BatteryReading.unavailable()
        );

        Map<String, Object> result = execute(
                capabilities,
                AndroidDemoCapabilities.STRING_DIGEST,
                "{\"algorithm\":\"MD5\","
                        + "\"value\":\"\\u4f60\\u597d\"}"
        );

        assertEquals("MD5", result.get("algorithm"));
        assertEquals("\u4f60\u597d", result.get("input"));
        assertEquals(
                "7eca689f0d3389d9dea66ae112e5cfd7",
                result.get("digest")
        );
        assertEquals(1, capabilities.snapshot().processedCommands());
        assertEquals(
                AndroidDemoCapabilities.STRING_DIGEST,
                capabilities.snapshot().lastEvent()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> execute(
                        capabilities,
                        AndroidDemoCapabilities.STRING_DIGEST,
                        "{}"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> execute(
                        capabilities,
                        AndroidDemoCapabilities.STRING_DIGEST,
                        "{\"algorithm\":\"MD5\",\"value\":1}"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> execute(
                        capabilities,
                        AndroidDemoCapabilities.STRING_DIGEST,
                        "{\"algorithm\":\"SHA-256\","
                                + "\"value\":\"hello\"}"
                )
        );
        assertEquals(1, capabilities.snapshot().processedCommands());
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

    private static Map<String, Object> execute(
            AndroidDemoCapabilities capabilities,
            String eventCode
    ) throws Exception {
        return execute(capabilities, eventCode, "{}");
    }

    private static Map<String, Object> execute(
            AndroidDemoCapabilities capabilities,
            String eventCode,
            String payload
    ) throws Exception {
        WorkerEventDefinition<?> definition =
                definitions(capabilities).get(eventCode);
        return executeDefinition(definition, payload);
    }

    private static <P> Map<String, Object> executeDefinition(
            WorkerEventDefinition<P> definition,
            String payload
    ) throws Exception {
        P parameters = definition.parameterResolver().resolve(payload);
        return Jsons.parseObject(
                definition.handler().execute(parameters)
        );
    }

    private static Map<String, WorkerEventDefinition<?>> definitions(
            AndroidDemoCapabilities capabilities
    ) {
        Map<String, WorkerEventDefinition<?>> result = new LinkedHashMap<>();
        for (WorkerEventDefinition<?> definition : capabilities.definitions()) {
            result.put(definition.eventName(), definition);
        }
        return result;
    }
}
