package com.xa.mass.base.jsondsl;

import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;

import java.util.List;
import java.util.Map;

/**
 * JsonDslEngine 重构后的 API 使用示例
 * 展示如何根据不同的 DSL 结构获得合适的返回类型
 */
public class JsonDslEngineExample {

    public static void main(String[] args) {
        // 注册类型（实际使用时应该在应用启动时注册）
        TypeRegistry.register("Device", "com.xa.mass.base.model.Device");
        TypeRegistry.register("Task", "com.xa.mass.base.model.Task");

        // 示例1：生成单个对象
        singleObjectExample();

        // 示例2：生成对象列表
        objectListExample();

        // 示例3：生成多个模型
        multipleModelsExample();

        // 示例4：使用便利方法
        convenienceMethodsExample();
    }

    /**
     * 示例1：生成单个对象
     * DSL 中 COUNT=1 或未指定 COUNT 时，返回单个对象
     */
    private static void singleObjectExample() {
        System.out.println("=== 生成单个对象 ===");
        
        String singleDeviceDsl = """
            {
                "MODEL": "Device",
                "FIELDS": {
                    "deviceId": "device-{i}",
                    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]},
                    "region": "region-{i}"
                }
            }
            """;

        // 返回单个 Device 对象
        Object result = JsonDslEngine.generate(singleDeviceDsl);
        System.out.println("返回类型: " + result.getClass().getSimpleName());
        System.out.println("结果: " + result);
        System.out.println();
    }

    /**
     * 示例2：生成对象列表
     * DSL 中 COUNT>1 时，返回 List<Object>
     */
    private static void objectListExample() {
        System.out.println("=== 生成对象列表 ===");
        
        String multipleDevicesDsl = """
            {
                "MODEL": "Device",
                "COUNT": 3,
                "FIELDS": {
                    "deviceId": "device-{i}",
                    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]},
                    "region": "region-{i}"
                }
            }
            """;

        // 返回 List<Device>
        Object result = JsonDslEngine.generate(multipleDevicesDsl);
        System.out.println("返回类型: " + result.getClass().getSimpleName());
        if (result instanceof List) {
            List<?> list = (List<?>) result;
            System.out.println("列表大小: " + list.size());
            list.forEach(System.out::println);
        }
        System.out.println();
    }

    /**
     * 示例3：生成多个模型
     * DSL 中包含多个 MODEL 时，返回 Map<String, Object>
     */
    private static void multipleModelsExample() {
        System.out.println("=== 生成多个模型 ===");
        
        String multipleModelsDsl = """
            {
                "device": {
                    "MODEL": "Device",
                    "FIELDS": {
                        "deviceId": "device-{i}",
                        "status": {"$CHOICE": ["ONLINE", "OFFLINE"]}
                    }
                },
                "task": {
                    "MODEL": "Task",
                    "FIELDS": {
                        "taskId": "task-{i}",
                        "priority": {"$CHOICE": ["HIGH", "MEDIUM", "LOW"]}
                    }
                }
            }
            """;

        // 返回 Map<String, Object>
        Object result = JsonDslEngine.generate(multipleModelsDsl);
        System.out.println("返回类型: " + result.getClass().getSimpleName());
        if (result instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) result;
            map.forEach((key, value) -> {
                System.out.println("模型 " + key + ": " + value.getClass().getSimpleName());
                System.out.println("值: " + value);
            });
        }
        System.out.println();
    }

    /**
     * 示例4：使用便利方法
     * 展示各种便利方法的使用
     */
    private static void convenienceMethodsExample() {
        System.out.println("=== 便利方法示例 ===");
        
        String deviceDsl = """
            {
                "MODEL": "Device",
                "COUNT": 2,
                "FIELDS": {
                    "deviceId": "device-{i}",
                    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]}
                }
            }
            """;

        // 强制获取单个对象
        Object single = JsonDslEngine.generateSingle(deviceDsl);
        System.out.println("generateSingle: " + single.getClass().getSimpleName());

        // 强制获取列表
        List<Object> list = JsonDslEngine.generateList(deviceDsl);
        System.out.println("generateAsList: " + list.size() + " 个对象");

        // 强制获取映射
        Map<String, Object> map = JsonDslEngine.generateMap(deviceDsl, "Device");
        System.out.println("generateAsMap: " + map.keySet());

        // 带类型转换
        List<Object> typedList = JsonDslEngine.generateTyped(deviceDsl, List.class);
        System.out.println("generateTyped(List): " + typedList.size() + " 个对象");
        System.out.println();
    }

    /**
     * 向后兼容性示例
     * 展示如何使用旧的 API
     */
    private static void backwardCompatibilityExample() {
        System.out.println("=== 向后兼容性示例 ===");
        
        String deviceDsl = """
            {
                "MODEL": "Device",
                "FIELDS": {
                    "deviceId": "device-{i}",
                    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]}
                }
            }
            """;

        // 旧的 API（已废弃，但仍然可用）
        List<Object> oldResult = JsonDslEngine.generateList(deviceDsl);
        System.out.println("旧 API 结果: " + oldResult.size() + " 个对象");

        // 新的推荐方式
        Object newResult = JsonDslEngine.generate(deviceDsl);
        System.out.println("新 API 结果类型: " + newResult.getClass().getSimpleName());
        System.out.println();
    }
} 