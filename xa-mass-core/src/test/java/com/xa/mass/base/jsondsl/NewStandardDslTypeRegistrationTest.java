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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 娴嬭瘯鏂版爣鍑?DSL 绯荤粺鐨勭被鍨嬫敞鍐屾満鍒?
 * <p>
 * 楠岃瘉鏂版爣鍑?DSL 绯荤粺鏄惁鐪熺殑涓嶉渶瑕佹彁鍓嶆敞鍐岀被鍨?
 * </p>
 */
public class NewStandardDslTypeRegistrationTest {

    @Test
    public void testNewStandardDslWithoutTypeRegistration() {
        // 娴嬭瘯锛氭柊鏍囧噯 DSL 绯荤粺搴旇鏀寔鐩存帴浣跨敤鍏ㄧ被鍚嶏紝鏃犻渶娉ㄥ唽

        // 1. 鍒涘缓 DSL 瀹氫箟锛堜娇鐢ㄥ叏绫诲悕锛?
        JsonDslDefinition definition = new JsonDslDefinition("test_device_generator", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("娴嬭瘯璁惧鐢熸垚鍣?);
        definition.setAuthor("test");

        // 2. 璁剧疆涓婁笅鏂囷紙浣跨敤鍏ㄧ被鍚嶏紝涓嶆敞鍐岋級
        JsonDslContext context = new JsonDslContext("com.xa.mass.base.model.Device", 2);
        context.setScopeName("Device");
        definition.setContext(context);

        // 3. 璁剧疆瀛楁 DSL
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("deviceId", Map.of("$JOIN", Arrays.asList("test-device-", "&.index")));
        fieldDsl.put("status", Map.of("$CHOICE", Arrays.asList("ONLINE", "OFFLINE")));
        fieldDsl.put("deviceGroupId", Map.of("$CHOICE", Arrays.asList("us", "gb")));
        definition.setFieldDsl(fieldDsl);

        // 4. 楠岃瘉 DSL
        definition.validate();

        // 5. 浣跨敤鏂扮殑澶勭悊鍣ㄥ紩鎿庣敓鎴愭暟鎹?
        ProcessingContext processingContext = new ProcessingContext("test");
        List<Device> devices = JsonDslProcessorEngine.process(definition, processingContext, Device.class);

        // 6. 楠岃瘉缁撴灉
        assertNotNull(devices);
        assertEquals(2, devices.size());

        for (Device device : devices) {
            assertNotNull(device.getDeviceId());
            assertTrue(device.getDeviceId().startsWith("test-device-"));
            assertNotNull(device.getStatus());
            assertNotNull(device.getDeviceGroupId());
            assertTrue(Arrays.asList("us", "gb").contains(device.getDeviceGroupId()));
        }

        System.out.println("鉁?鏂版爣鍑?DSL 绯荤粺鎴愬姛浣跨敤鍏ㄧ被鍚嶇敓鎴愭暟鎹紝鏃犻渶鎻愬墠娉ㄥ唽绫诲瀷");
    }

    @Test
    public void testNewStandardDslWithComplexFields() {
        // 娴嬭瘯锛氭柊鏍囧噯 DSL 绯荤粺鏀寔澶嶆潅瀛楁锛屼篃鏃犻渶娉ㄥ唽

        // 1. 鍒涘缓 DSL 瀹氫箟
        JsonDslDefinition definition = new JsonDslDefinition("complex_device_generator", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("娴嬭瘯澶嶆潅瀛楁鐢熸垚鍣?);
        definition.setAuthor("test");

        // 2. 璁剧疆涓婁笅鏂囷紙浣跨敤鍏ㄧ被鍚嶏級
        JsonDslContext context = new JsonDslContext("com.xa.mass.base.model.Device", 1);
        context.setScopeName("Device");
        definition.setContext(context);

        // 3. 璁剧疆瀛楁 DSL锛堝寘鍚鏉傚瓧娈碉級
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("deviceId", "complex-device-001");
        fieldDsl.put("status", "ONLINE");
        fieldDsl.put("deviceGroupId", "us");
        fieldDsl.put("agentVersion", "2.0.1");

        // 绠€鍗曞瓧娈?
        fieldDsl.put("onlineStrategy", "100");

        definition.setFieldDsl(fieldDsl);

        // 4. 楠岃瘉 DSL
        definition.validate();

        // 5. 浣跨敤鏂扮殑澶勭悊鍣ㄥ紩鎿庣敓鎴愭暟鎹?
        ProcessingContext processingContext = new ProcessingContext("test");
        List<Device> devices = JsonDslProcessorEngine.process(definition, processingContext, Device.class);

        // 6. 楠岃瘉缁撴灉
        assertNotNull(devices);
        assertEquals(1, devices.size());

        Device device = devices.get(0);
        assertEquals("complex-device-001", device.getDeviceId());
        assertEquals("ONLINE", device.getStatus().name());
        assertEquals("us", device.getDeviceGroupId());
        assertEquals("2.0.1", device.getAgentVersion());
        assertEquals("100", device.getOnlineStrategy());

        System.out.println("鉁?鏂版爣鍑?DSL 绯荤粺鎴愬姛浣跨敤澶嶆潅瀛楁鐢熸垚鏁版嵁锛屾棤闇€鎻愬墠娉ㄥ唽绫诲瀷");
    }

    @Test
    public void testNewStandardDslFromJsonWithoutRegistration() {
        // 娴嬭瘯锛氫粠 JSON 瑙ｆ瀽鐨?DSL 涔熸敮鎸佸叏绫诲悕锛屾棤闇€娉ㄥ唽

        String jsonDsl = """
                {
                  "unique_id": "json_test_generator",
                  "type": "generate",
                  "priority": 1,
                  "desc": "JSON 娴嬭瘯鐢熸垚鍣?,
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

        // 1. 瑙ｆ瀽 JSON
        JsonDslDefinition definition = JsonDslParser.parse(jsonDsl);

        // 2. 楠岃瘉瑙ｆ瀽缁撴灉
        assertEquals("json_test_generator", definition.getUniqueId());
        assertEquals(JsonDslDefinition.DslType.GENERATE, definition.getType());
        assertEquals("com.xa.mass.base.model.Device", definition.getContext().getModel());

        // 3. 浣跨敤鏂扮殑澶勭悊鍣ㄥ紩鎿庣敓鎴愭暟鎹?
        ProcessingContext processingContext = new ProcessingContext("test");
        List<Device> devices = JsonDslProcessorEngine.process(definition, processingContext, Device.class);

        // 4. 楠岃瘉缁撴灉
        assertNotNull(devices);
        assertEquals(1, devices.size());

        Device device = devices.get(0);
        assertEquals("json-device-001", device.getDeviceId());
        assertEquals("ONLINE", device.getStatus().name());
        assertEquals("gb", device.getDeviceGroupId());

        System.out.println("鉁?浠?JSON 瑙ｆ瀽鐨勬柊鏍囧噯 DSL 涔熸敮鎸佸叏绫诲悕锛屾棤闇€鎻愬墠娉ㄥ唽绫诲瀷");
    }
} 