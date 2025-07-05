package com.xa.mass.base.jsondsl;

import com.xa.mass.base.jsondsl.builtin.TypeRegistry;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;

import java.util.List;
import java.util.Map;

/**
 * 新的简化 API 使用示例
 */
public class SimplifiedApiExample {

    public static void main(String[] args) {
        // 注册类型
        TypeRegistry.register("Device", Device.class.getName());
        TypeRegistry.register("Task", Task.class.getName());

        System.out.println("=== 单模型生成示例 ===");
        singleModelExample();

        System.out.println("\n=== 多模型生成示例 ===");
        multipleModelsExample();
    }

    /**
     * 单模型生成示例
     */
    private static void singleModelExample() {
        // 1. 生成无类型列表
        String deviceDsl = """
            {
                "MODEL": "Device",
                "COUNT": 3,
                "FIELDS": {
                    "deviceId": "{{@uuid}}",
                    "status": "ONLINE"
                }
            }
            """;

        List<Object> deviceList = JsonDslEngine.generateList(deviceDsl);
        System.out.println("生成了 " + deviceList.size() + " 个设备");

        // 2. 生成有类型列表
        List<Device> typedDeviceList = JsonDslEngine.generateList(deviceDsl, Device.class);
        System.out.println("生成了 " + typedDeviceList.size() + " 个类型化设备");
        
        for (Device device : typedDeviceList) {
            System.out.println("设备ID: " + device.getDeviceId() + ", 状态: " + device.getStatus());
        }
    }

    /**
     * 多模型生成示例
     */
    private static void multipleModelsExample() {
        String multiModelDsl = """
            {
                "devices": {
                    "MODEL": "Device",
                    "COUNT": 2,
                    "FIELDS": {
                        "deviceId": "{{@uuid}}",
                        "status": "ONLINE"
                    }
                },
                "tasks": {
                    "MODEL": "Task",
                    "COUNT": 3,
                    "FIELDS": {
                        "tid": "{{@uuid}}",
                        "taskName": "测试任务{{&Task.index}}",
                        "status": "READY"
                    }
                }
            }
            """;

        Map<String, List<Object>> result = JsonDslEngine.generateMap(multiModelDsl);
        
        System.out.println("生成了 " + result.size() + " 种模型:");
        
        // 处理设备
        List<Object> deviceList = result.get("devices");
        System.out.println("- 设备: " + deviceList.size() + " 个");
        for (Object obj : deviceList) {
            Device device = (Device) obj;
            System.out.println("  设备ID: " + device.getDeviceId());
        }
        
        // 处理任务
        List<Object> taskList = result.get("tasks");
        System.out.println("- 任务: " + taskList.size() + " 个");
        for (Object obj : taskList) {
            Task task = (Task) obj;
            System.out.println("  任务: " + task.getTaskName() + ", ID: " + task.getTid());
        }
    }
} 