package com.xa.mass.base.mock;

import com.xa.mass.base.model.Device;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MockTemplateEngineTest {
    @BeforeAll
    static void setup() {
        MockTypeRegistry.clear();
        MockTypeRegistry.register("Device", Device.class);
        MockTypeRegistry.register("Region", "com.xa.mass.base.model.Region");
        MockTypeRegistry.register("Token", "com.xa.mass.base.model.Token");
    }

    @Test
    void testSimpleMock() {
        String json = """
        {
          "MODEL": "Device",
          "FIELDS": {
            "deviceId": {"$UUID": true},
            "status": {"$CHOICE": ["ONLINE", "OFFLINE"]}
          }
        }
        """;
        List<Object> result = MockTemplateEngine.generate(json);
        assertEquals(1, result.size());
        Object device = result.get(0);
        assertNotNull(device);
        assertEquals("com.xa.mass.base.model.Device", device.getClass().getName());
    }

    @Test
    void testCountAndContext() {
        String json = """
        {
          "MODEL": "Device",
          "COUNT": 3,
          "FIELDS": {
            "deviceId": "device-{i}"
          }
        }
        """;
        List<Object> result = MockTemplateEngine.generate(json);
        assertEquals(3, result.size());
    }

    @Test
    void testNestedModel() {
        String json = """
        {
          "MODEL": "Device",
          "FIELDS": {
            "region": {
              "MODEL": "Region",
              "FIELDS": {
                "regionId": {"$UUID": true},
                "name": "region-{i}"
              }
            }
          }
        }
        """;
        List<Object> result = MockTemplateEngine.generate(json);
        Object device = result.get(0);
        assertNotNull(device);
        // 反射校验 region 字段不为 null
        try {
            Object region = device.getClass().getDeclaredField("region");
            assertNotNull(region);
        } catch (Exception e) {
            fail("region 字段校验失败: " + e.getMessage());
        }
    }

    @Test
    void testListField() {
        String json = """
        {
          "MODEL": "Device",
          "FIELDS": {
            "tokens": {
              "TYPE": "LIST",
              "COUNT": 2,
              "MODEL": "Token",
              "FIELDS": {
                "tokenId": {"$UUID": true},
                "status": {"$CHOICE": ["VALID", "INVALID"]}
              }
            }
          }
        }
        """;
        List<Object> result = MockTemplateEngine.generate(json);
        Object device = result.get(0);
        assertNotNull(device);
        // 反射校验 tokens 字段为 List 且长度为2
        try {
            var field = device.getClass().getDeclaredField("tokens");
            field.setAccessible(true);
            Object tokens = field.get(device);
            assertTrue(tokens instanceof List);
            assertEquals(2, ((List<?>) tokens).size());
        } catch (Exception e) {
            fail("tokens 字段校验失败: " + e.getMessage());
        }
    }

    @Test
    void testUnregisteredTypeError() {
        String json = """
        {
          "MODEL": "NotExistModel",
          "FIELDS": {}
        }
        """;
        Exception ex = assertThrows(MockTemplateException.class, () -> MockTemplateEngine.generate(json));
        assertTrue(ex.getMessage().contains("未注册类型"));
    }

    @Test
    void testJoinFunction() {
        String json = """
        {
          "MODEL": "Region",
          "COUNT": 2,
          "FIELDS": {
            "regionId": {"$UUID": true},
            "name": {"$JOIN": ["region-", "{i}"]}
          }
        }
        """;
        List<Object> result = MockTemplateEngine.generate(json);
        assertEquals(2, result.size());
        Object region0 = result.get(0);
        Object region1 = result.get(1);
        try {
            var field = region0.getClass().getDeclaredField("name");
            field.setAccessible(true);
            String name0 = (String) field.get(region0);
            String name1 = (String) field.get(region1);
            assertEquals("region-0", name0);
            assertEquals("region-1", name1);
        } catch (Exception e) {
            fail("name 字段校验失败: " + e.getMessage());
        }
    }
} 