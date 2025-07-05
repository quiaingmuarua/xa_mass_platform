package com.xa.mass.base.jsondsl;

import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

/**
 * 验证重构后的 JsonDslEngine 功能
 */
public class RefactoredJsonDslTest {

    @Test
    public void testSingleObjectGeneration() {
        // 注册类型
        TypeRegistry.register("Device", Device.class.getName());
        
        String dsl = """
            {
                "MODEL": "Device",
                "FIELDS": {
                    "deviceId": "{{@uuid}}",
                    "status": "ONLINE"
                }
            }
            """;
        
        Object result = JsonDslEngine.generateSingle(dsl);
        assertNotNull(result);
        assertTrue(result instanceof Device);
        
        Device device = (Device) result;
        assertNotNull(device.getDeviceId());
        assertEquals("ONLINE", device.getStatus().name());
    }

    @Test
    public void testListGeneration() {
        // 注册类型
        TypeRegistry.register("Task", Task.class.getName());
        
        String dsl = """
            {
                "MODEL": "Task",
                "COUNT": 3,
                "FIELDS": {
                    "tid": "{{@uuid}}",
                    "taskName": "任务",
                    "status": "READY"
                }
            }
            """;
        
        var result = JsonDslEngine.generateList(dsl);
        assertNotNull(result);
        assertEquals(3, result.size());
        
        for (int i = 0; i < result.size(); i++) {
            Task task = (Task) result.get(i);
            assertNotNull(task.getTid());
            assertEquals("任务", task.getTaskName());
            assertEquals("READY", task.getStatus().name());
        }
    }

    @Test
    public void testMultipleModelsGeneration() {
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
        
        var result = JsonDslEngine.generateMap(dsl, "test");
        assertNotNull(result);
        assertEquals(2, result.size());
        
        assertTrue(result.containsKey("device"));
        assertTrue(result.containsKey("task"));
        
        Object deviceResult = result.get("device");
        Object taskResult = result.get("task");
        
        assertNotNull(deviceResult);
        assertNotNull(taskResult);
        
        // 多模型生成返回的是列表，取第一个元素
        if (deviceResult instanceof List<?> deviceList && !deviceList.isEmpty()) {
            Device device = (Device) deviceList.get(0);
            assertNotNull(device.getDeviceId());
        }
        
        if (taskResult instanceof List<?> taskList && !taskList.isEmpty()) {
            Task task = (Task) taskList.get(0);
            assertEquals("测试任务", task.getTaskName());
        }
    }

    @Test
    public void testReturnTypeAuto() {
        // 注册类型
        TypeRegistry.register("Device", Device.class.getName());
        
        // 单个对象 - 应该返回 Object
        String singleDsl = """
            {
                "MODEL": "Device",
                "FIELDS": {
                    "id": "{{@uuid}}",
                    "name": "设备",
                    "status": "ONLINE"
                }
            }
            """;
        
        Object singleResult = JsonDslEngine.generate(singleDsl, JsonDslEngine.ReturnType.AUTO);
        assertTrue(singleResult instanceof Device);
        
        // 多个对象 - 应该返回 List
        String multipleDsl = """
            {
                "MODEL": "Device",
                "COUNT": 2,
                "FIELDS": {
                    "id": "{{@uuid}}",
                    "name": "设备{{&Device.index}}",
                    "status": "ONLINE"
                }
            }
            """;
        
        Object multipleResult = JsonDslEngine.generate(multipleDsl, JsonDslEngine.ReturnType.AUTO);
        assertTrue(multipleResult instanceof List);
        assertEquals(2, ((List<?>) multipleResult).size());
    }
} 