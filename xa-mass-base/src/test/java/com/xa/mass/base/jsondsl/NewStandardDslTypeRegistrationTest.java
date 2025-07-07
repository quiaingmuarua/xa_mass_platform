package com.xa.mass.base.jsondsl;

import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.parser.JsonDslParser;
import com.xa.mass.base.model.Device;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试新标准 DSL 系统的类型注册机制
 * <p>
 * 验证新标准 DSL 系统是否真的不需要提前注册类型
 * </p>
 */
public class NewStandardDslTypeRegistrationTest {

    @Test
    public void testNewStandardDslWithoutTypeRegistration() {
        // 测试：新标准 DSL 系统应该支持直接使用全类名，无需注册
        
        // 1. 创建 DSL 定义（使用全类名）
        JsonDslDefinition definition = new JsonDslDefinition("test_device_generator", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("测试设备生成器");
        definition.setAuthor("test");
        
        // 2. 设置上下文（使用全类名，不注册）
        JsonDslContext context = new JsonDslContext("com.xa.mass.base.model.Device", 2);
        context.setScopeName("Device");
        definition.setContext(context);
        
        // 3. 设置字段 DSL
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("deviceId", Map.of("$JOIN", Arrays.asList("test-device-", "&.index")));
        fieldDsl.put("status", Map.of("$CHOICE", Arrays.asList("ONLINE", "OFFLINE")));
        fieldDsl.put("groupId", Map.of("$CHOICE", Arrays.asList("us", "gb")));
        definition.setFieldDsl(fieldDsl);
        
        // 4. 验证 DSL
        definition.validate();
        
        // 5. 转换为传统格式并生成数据
        String legacyFormat = JsonDslParser.toLegacyFormat(definition);
        List<Device> devices = JsonDslEngine.generateList(legacyFormat, Device.class);
        
        // 6. 验证结果
        assertNotNull(devices);
        assertEquals(2, devices.size());
        
        for (Device device : devices) {
            assertNotNull(device.getDeviceId());
            assertTrue(device.getDeviceId().startsWith("test-device-"));
            assertNotNull(device.getStatus());
            assertNotNull(device.getGroupId());
            assertTrue(Arrays.asList("us", "gb").contains(device.getGroupId()));
        }
        
        System.out.println("✓ 新标准 DSL 系统成功使用全类名生成数据，无需提前注册类型");
    }
    
    @Test
    public void testNewStandardDslWithComplexFields() {
        // 测试：新标准 DSL 系统支持复杂字段，也无需注册
        
        // 1. 创建 DSL 定义
        JsonDslDefinition definition = new JsonDslDefinition("complex_device_generator", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("测试复杂字段生成器");
        definition.setAuthor("test");
        
        // 2. 设置上下文（使用全类名）
        JsonDslContext context = new JsonDslContext("com.xa.mass.base.model.Device", 1);
        context.setScopeName("Device");
        definition.setContext(context);
        
        // 3. 设置字段 DSL（包含复杂字段）
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("deviceId", "complex-device-001");
        fieldDsl.put("status", "ONLINE");
        fieldDsl.put("groupId", "us");
        fieldDsl.put("agentVersion", "2.0.1");
        
        // 简单字段
        fieldDsl.put("onlineStrategy", "100");
        
        definition.setFieldDsl(fieldDsl);
        
        // 4. 验证 DSL
        definition.validate();
        
        // 5. 转换为传统格式并生成数据
        String legacyFormat = JsonDslParser.toLegacyFormat(definition);
        List<Device> devices = JsonDslEngine.generateList(legacyFormat, Device.class);
        
        // 6. 验证结果
        assertNotNull(devices);
        assertEquals(1, devices.size());
        
        Device device = devices.get(0);
        assertEquals("complex-device-001", device.getDeviceId());
        assertEquals("ONLINE", device.getStatus().name());
        assertEquals("us", device.getGroupId());
        assertEquals("2.0.1", device.getAgentVersion());
        assertEquals("100", device.getOnlineStrategy());
        
        System.out.println("✓ 新标准 DSL 系统成功使用复杂字段生成数据，无需提前注册类型");
    }
    
    @Test
    public void testNewStandardDslFromJsonWithoutRegistration() {
        // 测试：从 JSON 解析的 DSL 也支持全类名，无需注册
        
        String jsonDsl = """
            {
              "unique_id": "json_test_generator",
              "type": "generate",
              "priority": 1,
              "desc": "JSON 测试生成器",
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
                "groupId": "gb"
              }
            }
            """;
        
        // 1. 解析 JSON
        JsonDslDefinition definition = JsonDslParser.parse(jsonDsl);
        
        // 2. 验证解析结果
        assertEquals("json_test_generator", definition.getUniqueId());
        assertEquals(JsonDslDefinition.DslType.GENERATE, definition.getType());
        assertEquals("com.xa.mass.base.model.Device", definition.getContext().getModel());
        
        // 3. 生成数据
        String legacyFormat = JsonDslParser.toLegacyFormat(definition);
        List<Device> devices = JsonDslEngine.generateList(legacyFormat, Device.class);
        
        // 4. 验证结果
        assertNotNull(devices);
        assertEquals(1, devices.size());
        
        Device device = devices.get(0);
        assertEquals("json-device-001", device.getDeviceId());
        assertEquals("ONLINE", device.getStatus().name());
        assertEquals("gb", device.getGroupId());
        
        System.out.println("✓ 从 JSON 解析的新标准 DSL 也支持全类名，无需提前注册类型");
    }
} 