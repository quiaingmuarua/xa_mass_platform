package com.xa.mass.base.jsondsl;

import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.parser.JsonDslParser;
import com.xa.mass.base.jsondsl.processor.JsonDslProcessorEngine;
import com.xa.mass.base.jsondsl.processor.ProcessingContext;
import com.xa.mass.base.model.Device;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewStandardDslTypeRegistrationTest {

    @Test
    void processesGenerateDslWithoutManualTypeRegistration() {
        JsonDslDefinition definition = new JsonDslDefinition("test_device_generator", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("Generate devices without type registry setup");
        definition.setAuthor("test");

        JsonDslContext context = new JsonDslContext("com.xa.mass.base.model.Device", 2);
        context.setScopeName("Device");
        definition.setContext(context);

        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("deviceId", Map.of("$JOIN", Arrays.asList("test-device-", "&.index")));
        fieldDsl.put("status", Map.of("$CHOICE", Arrays.asList("ONLINE", "OFFLINE")));
        fieldDsl.put("deviceGroupId", Map.of("$CHOICE", Arrays.asList("us", "gb")));
        definition.setFieldDsl(fieldDsl);
        definition.validate();

        List<Device> devices = JsonDslProcessorEngine.process(definition, new ProcessingContext("test"), Device.class);

        assertNotNull(devices);
        assertEquals(2, devices.size());
        for (Device device : devices) {
            assertNotNull(device.getDeviceId());
            assertTrue(device.getDeviceId().startsWith("test-device-"));
            assertNotNull(device.getStatus());
            assertNotNull(device.getDeviceGroupId());
            assertTrue(Arrays.asList("us", "gb").contains(device.getDeviceGroupId()));
        }
    }

    @Test
    void processesGenerateDslWithConcreteFields() {
        JsonDslDefinition definition = new JsonDslDefinition("complex_device_generator", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("Generate one concrete device");
        definition.setAuthor("test");

        JsonDslContext context = new JsonDslContext("com.xa.mass.base.model.Device", 1);
        context.setScopeName("Device");
        definition.setContext(context);

        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("deviceId", "complex-device-001");
        fieldDsl.put("status", "ONLINE");
        fieldDsl.put("deviceGroupId", "us");
        fieldDsl.put("agentVersion", "2.0.1");
        fieldDsl.put("onlineStrategy", "100");
        definition.setFieldDsl(fieldDsl);
        definition.validate();

        List<Device> devices = JsonDslProcessorEngine.process(definition, new ProcessingContext("test"), Device.class);

        assertNotNull(devices);
        assertEquals(1, devices.size());
        Device device = devices.get(0);
        assertEquals("complex-device-001", device.getDeviceId());
        assertEquals("ONLINE", device.getStatus().name());
        assertEquals("us", device.getDeviceGroupId());
        assertEquals("2.0.1", device.getAgentVersion());
        assertEquals("100", device.getOnlineStrategy());
    }

    @Test
    void parsesAndProcessesJsonDslWithoutRegistration() {
        String jsonDsl = """
                {
                  "unique_id": "json_test_generator",
                  "type": "generate",
                  "priority": 1,
                  "desc": "JSON generate example",
                  "version": "1.0",
                  "author": "test",
                  "tags": ["json", "test"],
                  "context": {
                    "MODEL": "com.xa.mass.base.model.Device",
                    "COUNT": 1,
                    "scope_name": "Device",
                    "debug": true
                  },
                  "fieldDsl": {
                    "deviceId": "json-device-001",
                    "status": "ONLINE",
                    "deviceGroupId": "gb"
                  }
                }
                """;

        JsonDslDefinition definition = JsonDslParser.parse(jsonDsl);

        assertEquals("json_test_generator", definition.getUniqueId());
        assertEquals(JsonDslDefinition.DslType.GENERATE, definition.getType());
        assertEquals("com.xa.mass.base.model.Device", definition.getContext().getModel());

        List<Device> devices = JsonDslProcessorEngine.process(definition, new ProcessingContext("test"), Device.class);

        assertNotNull(devices);
        assertEquals(1, devices.size());
        Device device = devices.get(0);
        assertEquals("json-device-001", device.getDeviceId());
        assertEquals("ONLINE", device.getStatus().name());
        assertEquals("gb", device.getDeviceGroupId());
    }
}
