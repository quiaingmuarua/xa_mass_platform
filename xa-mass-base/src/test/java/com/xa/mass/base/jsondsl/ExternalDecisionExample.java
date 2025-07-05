package com.xa.mass.base.jsondsl;

import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;

import java.util.List;
import java.util.Map;

/**
 * 外部判断逻辑示例
 * 展示如何根据业务需求决定返回类型
 */
public class ExternalDecisionExample {
    
    public static void main(String[] args) {
        // 注册类型
        TypeRegistry.register("Device", Device.class);
        TypeRegistry.register("Task", Task.class);
        
        System.out.println("=== 外部判断逻辑示例 ===");
        
        // 示例1：根据业务场景决定返回类型
        businessScenarioExample();
        
        // 示例2：根据 DSL 结构决定返回类型
        dslStructureExample();
        
        // 示例3：用户偏好设置
        userPreferenceExample();
    }
    
    /**
     * 示例1：根据业务场景决定返回类型
     */
    private static void businessScenarioExample() {
        System.out.println("\n--- 业务场景示例 ---");
        
        String deviceDsl = """
            {
                "MODEL": "Device",
                "COUNT": 3,
                "FIELDS": {
                    "deviceId": {"$JOIN": ["device-", "&.index"]},
                    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]}
                }
            }
            """;
        
        // 场景1：批量操作 - 需要列表
        System.out.println("场景1：批量操作");
        List<Object> devices = JsonDslEngine.generate(deviceDsl, JsonDslEngine.ReturnType.LIST);
        System.out.println("  生成了 " + devices.size() + " 个设备用于批量操作");
        
        // 场景2：单个操作 - 需要单个对象
        System.out.println("场景2：单个操作");
        Object device = JsonDslEngine.generate(deviceDsl, JsonDslEngine.ReturnType.SINGLE);
        System.out.println("  获取第一个设备: " + device);
        
        // 场景3：配置管理 - 需要映射
        System.out.println("场景3：配置管理");
        Map<String, Object> deviceMap = JsonDslEngine.generate(deviceDsl, JsonDslEngine.ReturnType.MAP);
        System.out.println("  设备配置映射: " + deviceMap.keySet());
    }
    
    /**
     * 示例2：根据 DSL 结构决定返回类型
     */
    private static void dslStructureExample() {
        System.out.println("\n--- DSL 结构判断示例 ---");
        
        // 单个对象 DSL
        String singleDsl = """
            {
                "MODEL": "Device",
                "FIELDS": {
                    "deviceId": "device-001",
                    "status": "ONLINE"
                }
            }
            """;
        
        // 多个对象 DSL
        String multipleDsl = """
            {
                "MODEL": "Device",
                "COUNT": 2,
                "FIELDS": {
                    "deviceId": {"$JOIN": ["device-", "&.index"]},
                    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]}
                }
            }
            """;
        
        // 多个模型 DSL
        String modelsDsl = """
            {
                "device": {
                    "MODEL": "Device",
                    "FIELDS": {
                        "deviceId": "device-001",
                        "status": "ONLINE"
                    }
                },
                "task": {
                    "MODEL": "Task",
                    "FIELDS": {
                        "taskId": "task-001",
                        "priority": "HIGH"
                    }
                }
            }
            """;
        
        // 根据 DSL 结构自动判断
        System.out.println("单个对象 DSL - 自动判断:");
        Object singleResult = JsonDslEngine.generate(singleDsl, JsonDslEngine.ReturnType.AUTO);
        System.out.println("  返回类型: " + singleResult.getClass().getSimpleName());
        
        System.out.println("多个对象 DSL - 自动判断:");
        Object multipleResult = JsonDslEngine.generate(multipleDsl, JsonDslEngine.ReturnType.AUTO);
        System.out.println("  返回类型: " + multipleResult.getClass().getSimpleName());
        
        System.out.println("多个模型 DSL - 自动判断:");
        Object modelsResult = JsonDslEngine.generate(modelsDsl, JsonDslEngine.ReturnType.AUTO);
        System.out.println("  返回类型: " + modelsResult.getClass().getSimpleName());
    }
    
    /**
     * 示例3：用户偏好设置
     */
    private static void userPreferenceExample() {
        System.out.println("\n--- 用户偏好设置示例 ---");
        
        String deviceDsl = """
            {
                "MODEL": "Device",
                "FIELDS": {
                    "deviceId": "device-001",
                    "status": "ONLINE"
                }
            }
            """;
        
        // 用户偏好：总是返回列表
        System.out.println("用户偏好：总是返回列表");
        List<Object> devices = JsonDslEngine.generate(deviceDsl, JsonDslEngine.ReturnType.LIST);
        System.out.println("  结果: " + devices.size() + " 个设备");
        
        // 用户偏好：总是返回单个对象
        System.out.println("用户偏好：总是返回单个对象");
        Object device = JsonDslEngine.generate(deviceDsl, JsonDslEngine.ReturnType.SINGLE);
        System.out.println("  结果: " + device.getClass().getSimpleName());
        
        // 用户偏好：总是返回映射
        System.out.println("用户偏好：总是返回映射");
        Map<String, Object> deviceMap = JsonDslEngine.generate(deviceDsl, JsonDslEngine.ReturnType.MAP);
        System.out.println("  结果: " + deviceMap.keySet());
    }
    
    /**
     * 示例4：动态决策逻辑
     */
    public static Object generateWithDecision(String jsonDsl, String operationType) {
        JsonDslEngine.ReturnType returnType;
        
        switch (operationType.toLowerCase()) {
            case "batch":
                returnType = JsonDslEngine.ReturnType.LIST;
                break;
            case "single":
                returnType = JsonDslEngine.ReturnType.SINGLE;
                break;
            case "config":
                returnType = JsonDslEngine.ReturnType.MAP;
                break;
            case "auto":
            default:
                returnType = JsonDslEngine.ReturnType.AUTO;
                break;
        }
        
        return JsonDslEngine.generate(jsonDsl, returnType);
    }
    
    /**
     * 示例5：基于 DSL 内容的智能决策
     */
    public static Object generateWithSmartDecision(String jsonDsl) {
        // 这里可以解析 DSL 内容来决定返回类型
        // 例如：检查 COUNT 字段、MODEL 字段等
        
        // 简化示例：总是使用 AUTO 模式
        return JsonDslEngine.generate(jsonDsl, JsonDslEngine.ReturnType.AUTO);
    }
} 