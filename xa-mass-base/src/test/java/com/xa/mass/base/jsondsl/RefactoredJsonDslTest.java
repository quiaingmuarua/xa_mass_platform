package com.xa.mass.base.jsondsl;

import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import com.xa.mass.base.jsondsl.builtin.TypeRegistry;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;

/**
 * 验证重构后的 JsonDslEngine 功能
 */
public class RefactoredJsonDslTest {

    @Test
    public void testSingleModelList() {
        // 注册类型
        TypeRegistry.register("Device", Device.class.getName());
        
        String dsl = """
            {
                "MODEL": "Device",
                "COUNT": 3,
                "FIELDS": {
                    "deviceId": "{{@uuid}}",
                    "status": "ONLINE"
                }
            }
            """;
        
        List<Object> result = JsonDslEngine.generateList(dsl);
        assertNotNull(result);
        assertEquals(3, result.size());
        
        for (Object obj : result) {
            assertTrue(obj instanceof Device);
            Device device = (Device) obj;
            assertNotNull(device.getDeviceId());
            assertEquals("ONLINE", device.getStatus().name());
        }
    }

    @Test
    public void testSingleModelTypedList() {
        // 注册类型
        TypeRegistry.register("Device", Device.class.getName());
        
        String dsl = """
            {
                "MODEL": "Device",
                "COUNT": 2,
                "FIELDS": {
                    "deviceId": "{{@uuid}}",
                    "status": "ONLINE"
                }
            }
            """;
        
        List<Device> result = JsonDslEngine.generateList(dsl, Device.class);
        assertNotNull(result);
        assertEquals(2, result.size());
        
        for (Device device : result) {
            assertNotNull(device.getDeviceId());
            assertEquals("ONLINE", device.getStatus().name());
        }
    }

    @Test
    public void testMultipleModelsMap() {
        // 注册类型
        TypeRegistry.register("Device", Device.class.getName());
        TypeRegistry.register("Task", Task.class.getName());
        
        String dsl = """
            {
                "device": {
                    "MODEL": "Device",
                    "COUNT": 2,
                    "FIELDS": {
                        "deviceId": "{{@uuid}}",
                        "status": "ONLINE"
                    }
                },
                "task": {
                    "MODEL": "Task",
                    "COUNT": 3,
                    "FIELDS": {
                        "tid": "{{@uuid}}",
                        "taskName": "测试任务",
                        "status": "READY"
                    }
                }
            }
            """;
        
        Map<String, List<Object>> result = JsonDslEngine.generateMap(dsl);
        assertNotNull(result);
        assertEquals(2, result.size());
        
        assertTrue(result.containsKey("device"));
        assertTrue(result.containsKey("task"));
        
        List<Object> deviceList = result.get("device");
        List<Object> taskList = result.get("task");
        
        assertEquals(2, deviceList.size());
        assertEquals(3, taskList.size());
        
        // 验证设备
        for (Object obj : deviceList) {
            assertTrue(obj instanceof Device);
            Device device = (Device) obj;
            assertNotNull(device.getDeviceId());
            assertEquals("ONLINE", device.getStatus().name());
        }
        
        // 验证任务
        for (Object obj : taskList) {
            assertTrue(obj instanceof Task);
            Task task = (Task) obj;
            assertNotNull(task.getTid());
            assertEquals("测试任务", task.getTaskName());
            assertEquals("READY", task.getStatus().name());
        }
    }

    @Test
    public void testSingleModelAsMap() {
        // 注册类型
        TypeRegistry.register("Device", Device.class.getName());
        
        String dsl = """
            {
                "MODEL": "Device",
                "COUNT": 2,
                "FIELDS": {
                    "deviceId": "{{@uuid}}",
                    "status": "ONLINE"
                }
            }
            """;
        
        Map<String, List<Object>> result = JsonDslEngine.generateMap(dsl);
        assertNotNull(result);
        assertEquals(1, result.size());
        
        assertTrue(result.containsKey("Device"));
        List<Object> deviceList = result.get("Device");
        assertEquals(2, deviceList.size());
        
        for (Object obj : deviceList) {
            assertTrue(obj instanceof Device);
            Device device = (Device) obj;
            assertNotNull(device.getDeviceId());
            assertEquals("ONLINE", device.getStatus().name());
        }
    }

    @Test
    public void testGenerateListWithMultipleModelsShouldThrowException() {
        // 注册类型
        TypeRegistry.register("Device", Device.class.getName());
        TypeRegistry.register("Task", Task.class.getName());
        
        String dsl = """
            {
                "device": {
                    "MODEL": "Device",
                    "FIELDS": {
                        "deviceId": "{{@uuid}}",
                        "status": "ONLINE"
                    }
                },
                "task": {
                    "MODEL": "Task",
                    "FIELDS": {
                        "tid": "{{@uuid}}",
                        "taskName": "测试任务",
                        "status": "READY"
                    }
                }
            }
            """;
        
        // 应该抛出异常，因为 generateList 不支持多模型
        assertThrows(JsonDslException.class, () -> {
            JsonDslEngine.generateList(dsl);
        });
    }
} 